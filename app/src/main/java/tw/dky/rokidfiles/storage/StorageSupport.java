package tw.dky.rokidfiles.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

final class StorageSupport {
    static final int MAX_THUMBNAIL_EDGE = 1024;
    static final int COPY_BUFFER_BYTES = 64 * 1024;

    private StorageSupport() {
    }

    static boolean isSafeDisplayName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")
                || trimmed.length() > 240) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char value = trimmed.charAt(i);
            if (value == '/' || value == '\\' || value == '\u0000'
                    || Character.isISOControl(value)) {
                return false;
            }
        }
        return true;
    }

    static String safeMimeType(String requested, String displayName) {
        String extension = "";
        if (displayName != null) {
            int dot = displayName.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < displayName.length()) {
                extension = displayName.substring(dot + 1);
            }
        }
        String detected = extension.isEmpty()
                ? null
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        extension.toLowerCase(Locale.US));
        if (detected == null
                || !(detected.startsWith("image/") || detected.startsWith("video/"))) {
            return "application/octet-stream";
        }
        if (requested != null) {
            String normalized = requested.trim().toLowerCase(Locale.US);
            int parameter = normalized.indexOf(';');
            if (parameter >= 0) normalized = normalized.substring(0, parameter).trim();
            if (sameMediaFamily(normalized, detected)) {
                return normalized;
            }
        }
        return detected;
    }

    static boolean hasRecognizedMediaSignature(File file, String mimeType) throws IOException {
        byte[] header = new byte[32];
        int count;
        try (InputStream input = new FileInputStream(file)) {
            count = input.read(header);
        }
        if (count < 2) return false;
        if (mimeType.startsWith("image/")) {
            return startsWith(header, count, 0xff, 0xd8, 0xff)
                    || startsWith(header, count, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                    || asciiAt(header, count, 0, "GIF87a")
                    || asciiAt(header, count, 0, "GIF89a")
                    || asciiAt(header, count, 0, "BM")
                    || (asciiAt(header, count, 0, "RIFF") && asciiAt(header, count, 8, "WEBP"))
                    || startsWith(header, count, 0x49, 0x49, 0x2a, 0x00)
                    || startsWith(header, count, 0x4d, 0x4d, 0x00, 0x2a)
                    || hasIsoBaseMediaBrand(header, count, true);
        }
        if (mimeType.startsWith("video/")) {
            return hasIsoBaseMediaBrand(header, count, false)
                    || startsWith(header, count, 0x1a, 0x45, 0xdf, 0xa3)
                    || (asciiAt(header, count, 0, "RIFF") && asciiAt(header, count, 8, "AVI "))
                    || startsWith(header, count, 0x00, 0x00, 0x01, 0xba)
                    || startsWith(header, count, 0x00, 0x00, 0x01, 0xb3);
        }
        return false;
    }

    private static boolean sameMediaFamily(String first, String second) {
        return first.startsWith("image/") && second.startsWith("image/")
                || first.startsWith("video/") && second.startsWith("video/");
    }

    private static boolean startsWith(byte[] value, int count, int... prefix) {
        if (count < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if ((value[index] & 0xff) != prefix[index]) return false;
        }
        return true;
    }

    private static boolean asciiAt(byte[] value, int count, int offset, String text) {
        if (offset < 0 || count - offset < text.length()) return false;
        for (int index = 0; index < text.length(); index++) {
            if ((value[offset + index] & 0xff) != text.charAt(index)) return false;
        }
        return true;
    }

    private static boolean hasIsoBaseMediaBrand(byte[] value, int count, boolean image) {
        if (!asciiAt(value, count, 4, "ftyp") || count < 12) return false;
        String brand = new String(Arrays.copyOfRange(value, 8, 12), java.nio.charset.StandardCharsets.US_ASCII)
                .toLowerCase(Locale.US);
        boolean stillImage = brand.equals("heic") || brand.equals("heix")
                || brand.equals("hevc") || brand.equals("hevx")
                || brand.equals("mif1") || brand.equals("msf1")
                || brand.equals("avif") || brand.equals("avis");
        return image == stillImage;
    }

    static MediaItem.Kind kindForMime(String mimeType) {
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            return MediaItem.Kind.DIRECTORY;
        }
        if (mimeType != null && mimeType.startsWith("image/")) {
            return MediaItem.Kind.IMAGE;
        }
        if (mimeType != null && mimeType.startsWith("video/")) {
            return MediaItem.Kind.VIDEO;
        }
        return null;
    }

    static int boundedDimension(int requested) {
        return Math.max(1, Math.min(requested, MAX_THUMBNAIL_EDGE));
    }

    static Bitmap loadContentThumbnail(
            Context context,
            ContentResolver resolver,
            Uri uri,
            String mimeType,
            int requestedWidth,
            int requestedHeight) throws IOException {
        int width = boundedDimension(requestedWidth);
        int height = boundedDimension(requestedHeight);

        try {
            Bitmap providerThumbnail = DocumentsContract.getDocumentThumbnail(
                    resolver, uri, new Point(width, height), new CancellationSignal());
            if (providerThumbnail != null) {
                return scaleDown(providerThumbnail, width, height);
            }
        } catch (IllegalArgumentException | UnsupportedOperationException | SecurityException ignored) {
            // MediaStore URIs and some DocumentsProviders do not implement this call.
        }

        if (mimeType != null && mimeType.startsWith("image/")) {
            return decodeSampled(resolver, uri, width, height);
        }
        if (mimeType != null && mimeType.startsWith("video/")) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, uri);
                Bitmap frame = retriever.getScaledFrameAtTime(
                        -1,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        width,
                        height);
                return frame == null ? null : scaleDown(frame, width, height);
            } catch (RuntimeException failure) {
                throw new IOException("Unable to decode video thumbnail", failure);
            } finally {
                retriever.release();
            }
        }
        return null;
    }

    static Bitmap loadFileThumbnail(
            File file,
            String mimeType,
            int requestedWidth,
            int requestedHeight) throws IOException {
        int width = boundedDimension(requestedWidth);
        int height = boundedDimension(requestedHeight);
        if (mimeType != null && mimeType.startsWith("image/")) {
            return decodeSampled(file, width, height);
        }
        if (mimeType != null && mimeType.startsWith("video/")) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                Bitmap frame = retriever.getScaledFrameAtTime(
                        -1,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        width,
                        height);
                return frame == null ? null : scaleDown(frame, width, height);
            } catch (RuntimeException failure) {
                throw new IOException("Unable to decode video thumbnail", failure);
            } finally {
                retriever.release();
            }
        }
        return null;
    }

    private static Bitmap decodeSampled(
            ContentResolver resolver, Uri uri, int width, int height) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) {
                throw new IOException("Provider returned a null input stream");
            }
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        BitmapFactory.Options options = sampledOptions(bounds, width, height);
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) {
                throw new IOException("Provider returned a null input stream");
            }
            Bitmap decoded = BitmapFactory.decodeStream(stream, null, options);
            return decoded == null ? null : scaleDown(decoded, width, height);
        } catch (OutOfMemoryError failure) {
            throw new IOException("Thumbnail exceeds the available memory", failure);
        }
    }

    private static Bitmap decodeSampled(File file, int width, int height) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = new FileInputStream(file)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        BitmapFactory.Options options = sampledOptions(bounds, width, height);
        try (InputStream stream = new FileInputStream(file)) {
            Bitmap decoded = BitmapFactory.decodeStream(stream, null, options);
            return decoded == null ? null : scaleDown(decoded, width, height);
        } catch (OutOfMemoryError failure) {
            throw new IOException("Thumbnail exceeds the available memory", failure);
        }
    }

    private static BitmapFactory.Options sampledOptions(
            BitmapFactory.Options bounds, int width, int height) throws IOException {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Invalid or unsupported image");
        }
        int ratio = Math.max(
                divideRoundUp(bounds.outWidth, width),
                divideRoundUp(bounds.outHeight, height));
        int sample = 1;
        while (sample <= ratio / 2) {
            sample <<= 1;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return options;
    }

    private static int divideRoundUp(int numerator, int denominator) {
        return (int) Math.min(Integer.MAX_VALUE,
                ((long) numerator + denominator - 1L) / denominator);
    }

    private static Bitmap scaleDown(Bitmap source, int maxWidth, int maxHeight) {
        if (source.getWidth() <= maxWidth && source.getHeight() <= maxHeight) {
            return source;
        }
        double ratio = Math.min(
                (double) maxWidth / source.getWidth(),
                (double) maxHeight / source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }
}
