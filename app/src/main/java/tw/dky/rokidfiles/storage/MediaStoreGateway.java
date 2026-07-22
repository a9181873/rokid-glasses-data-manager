package tw.dky.rokidfiles.storage;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Read-only fallback when all-files access has not been granted. */
public final class MediaStoreGateway implements StorageGateway {
    private final Context context;
    private final ContentResolver resolver;
    private final String volumeName;
    private final String allowedVolumeName;
    private final Uri collectionUri;
    private final File externalRoot;

    public MediaStoreGateway(Context context) {
        this(context, Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.VOLUME_EXTERNAL
                : "external");
    }

    public MediaStoreGateway(Context context, String volumeName) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.volumeName = Objects.requireNonNull(volumeName, "volumeName");
        this.allowedVolumeName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && MediaStore.VOLUME_EXTERNAL.equals(volumeName)
                ? MediaStore.VOLUME_EXTERNAL_PRIMARY : volumeName;
        this.collectionUri = MediaStore.Files.getContentUri(volumeName);
        this.externalRoot = Environment.getExternalStorageDirectory().getAbsoluteFile();
    }

    @Override
    public boolean isAvailable() {
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public MediaItem.Backend getBackend() {
        return MediaItem.Backend.MEDIA_STORE;
    }

    @Override
    public StorageCapacity getCapacitySummary() {
        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
        return StorageCapacity.basic(stat.getTotalBytes(), stat.getAvailableBytes());
    }

    @Override
    public MediaPage list(MediaQuery query) throws IOException, SecurityException {
        Objects.requireNonNull(query, "query");
        ensureAvailable();
        if (query.isTrash()) {
            // This fallback intentionally never requests write/trash access.
            return new MediaPage(Collections.emptyList(), query.getOffset(), false);
        }
        if (query.getParentId() != null) {
            throw new IllegalArgumentException("MediaStore fallback has no browsable directories");
        }

        QueryFilter filter = queryFilter(null);
        String[] projection = projection();
        List<MediaItem> items = new ArrayList<>(query.getLimit());
        Set<String> seenFiles = new HashSet<>();
        int acceptedBeforePage = 0;
        boolean hasMore = false;
        try (Cursor cursor = resolver.query(
                collectionUri,
                projection,
                filter.selection,
                filter.arguments,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC, "
                        + MediaStore.MediaColumns._ID + " DESC")) {
            if (cursor == null) {
                throw new IOException("MediaStore returned a null cursor");
            }
            while (cursor.moveToNext()) {
                MediaItem item;
                try {
                    item = fromCursor(cursor);
                    if (!isOpenable(item.getUri())) continue;
                } catch (IOException | SecurityException staleOrRejected) {
                    // MediaStore 偶爾保留已不存在的舊資料列；不可開啟的項目不應出現在清單。
                    continue;
                }
                if (!seenFiles.add(MediaIdentity.logicalKey(item))) continue;
                if (acceptedBeforePage++ < query.getOffset()) continue;
                if (items.size() >= query.getLimit()) {
                    hasMore = true;
                    break;
                }
                items.add(item);
            }
        }
        return new MediaPage(items, query.getOffset() + items.size(), hasMore);
    }

    @Override
    public MediaItem getItem(String id) throws IOException, SecurityException {
        ensureAvailable();
        long rowId = parseId(id);
        QueryFilter filter = queryFilter(rowId);
        try (Cursor cursor = resolver.query(
                collectionUri,
                projection(),
                filter.selection,
                filter.arguments,
                null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new FileNotFoundException("MediaStore item no longer exists");
            }
            return fromCursor(cursor);
        }
    }

    @Override
    public InputStream openInputStream(String id) throws IOException, SecurityException {
        MediaItem item = getItem(id);
        InputStream stream = resolver.openInputStream(item.getUri());
        if (stream == null) {
            throw new IOException("MediaStore returned a null input stream");
        }
        return stream;
    }

    @Override
    public AssetFileDescriptor openAssetFileDescriptor(String id)
            throws IOException, SecurityException {
        MediaItem item = getItem(id);
        AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(item.getUri(), "r");
        if (descriptor == null) {
            throw new IOException("MediaStore returned a null descriptor");
        }
        return descriptor;
    }

    @Override
    public Bitmap loadThumbnail(String id, int width, int height)
            throws IOException, SecurityException {
        MediaItem item = getItem(id);
        return StorageSupport.loadContentThumbnail(
                context, resolver, item.getUri(), item.getMimeType(), width, height);
    }

    @Override
    public StorageOperationResult rename(String id, String newDisplayName) {
        return readOnly("rename");
    }

    @Override
    public StorageOperationResult moveToTrash(String id) {
        return readOnly("trash");
    }

    @Override
    public StorageOperationResult restore(String id) {
        return readOnly("restore");
    }

    public String getVolumeName() {
        return volumeName;
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new SecurityException("READ_EXTERNAL_STORAGE permission is missing");
        }
    }

    private MediaItem fromCursor(Cursor cursor) throws IOException {
        long rowId = getLong(cursor, MediaStore.MediaColumns._ID, -1L);
        if (rowId < 0) {
            throw new IOException("MediaStore omitted row ID");
        }
        int mediaType = (int) getLong(
                cursor, MediaStore.Files.FileColumns.MEDIA_TYPE, 0L);
        MediaItem.Kind kind;
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
            kind = MediaItem.Kind.IMAGE;
        } else if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
            kind = MediaItem.Kind.VIDEO;
        } else {
            throw new IOException("Unexpected non-media row");
        }
        Uri uri = Uri.withAppendedPath(collectionUri, Long.toString(rowId));
        String name = getString(cursor, MediaStore.MediaColumns.DISPLAY_NAME, "Unnamed");
        String mime = getString(cursor, MediaStore.MediaColumns.MIME_TYPE,
                kind == MediaItem.Kind.IMAGE ? "image/*" : "video/*");
        long modifiedSeconds = getLong(cursor, MediaStore.MediaColumns.DATE_MODIFIED, 0L);
        long modifiedMillis = modifiedSeconds > Long.MAX_VALUE / 1000L
                ? Long.MAX_VALUE
                : modifiedSeconds * 1000L;
        String relativePath;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String rowVolume = getString(cursor, MediaStore.MediaColumns.VOLUME_NAME, null);
            relativePath = getString(cursor, MediaStore.MediaColumns.RELATIVE_PATH, null);
            if (!allowedVolumeName.equals(rowVolume)
                    || !MediaAllowlist.containsRelativePath(relativePath)) {
                throw new SecurityException("MediaStore row is outside the media allowlist");
            }
        } else {
            relativePath = MediaAllowlist.legacyParentRelativePath(
                    externalRoot, getString(cursor, MediaStore.MediaColumns.DATA, null));
            if (relativePath == null) {
                throw new SecurityException("MediaStore row is outside the media allowlist");
            }
        }
        return new MediaItem(
                encodeId(rowId),
                null,
                uri,
                name,
                mime,
                kind,
                MediaItem.Backend.MEDIA_STORE,
                getLong(cursor, MediaStore.MediaColumns.SIZE, -1L),
                modifiedMillis,
                relativePath,
                false,
                MediaItem.CAPABILITY_READ | MediaItem.CAPABILITY_THUMBNAIL);
    }

    private boolean isOpenable(Uri uri) {
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
            return descriptor != null;
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    private static String[] projection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.VOLUME_NAME,
                    MediaStore.Files.FileColumns.MEDIA_TYPE
            };
        }
        return new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATA,
                MediaStore.Files.FileColumns.MEDIA_TYPE
        };
    }

    private QueryFilter queryFilter(Long rowId) {
        StringBuilder selection = new StringBuilder();
        List<String> arguments = new ArrayList<>();
        if (rowId != null) {
            selection.append(MediaStore.MediaColumns._ID).append("=? AND ");
            arguments.add(Long.toString(rowId));
        }
        selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE).append(" IN (?, ?)");
        arguments.add(Integer.toString(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE));
        arguments.add(Integer.toString(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection.append(" AND ").append(MediaStore.MediaColumns.VOLUME_NAME).append("=?")
                    .append(" AND (");
            arguments.add(allowedVolumeName);
            for (int index = 0; index < MediaAllowlist.RELATIVE_ROOTS.size(); index++) {
                if (index > 0) selection.append(" OR ");
                selection.append(MediaStore.MediaColumns.RELATIVE_PATH)
                        .append("=? OR ")
                        .append(MediaStore.MediaColumns.RELATIVE_PATH)
                        .append(" GLOB ?");
                String root = MediaAllowlist.RELATIVE_ROOTS.get(index);
                arguments.add(root + "/");
                arguments.add(root + "/*");
            }
            selection.append(") AND ").append(MediaStore.MediaColumns.IS_PENDING).append("=0");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                selection.append(" AND ").append(MediaStore.MediaColumns.IS_TRASHED).append("=0");
            }
        } else {
            selection.append(" AND (");
            for (int index = 0; index < MediaAllowlist.RELATIVE_ROOTS.size(); index++) {
                if (index > 0) selection.append(" OR ");
                selection.append(MediaStore.MediaColumns.DATA).append(" GLOB ?");
                arguments.add(new File(externalRoot, MediaAllowlist.RELATIVE_ROOTS.get(index))
                        .getAbsolutePath() + "/*");
            }
            selection.append(')');
        }
        return new QueryFilter(selection.toString(), arguments.toArray(new String[0]));
    }

    private static final class QueryFilter {
        final String selection;
        final String[] arguments;

        QueryFilter(String selection, String[] arguments) {
            this.selection = selection;
            this.arguments = arguments;
        }
    }

    private static StorageOperationResult readOnly(String operation) {
        return StorageOperationResult.unsupported(
                "MediaStore fallback is read-only; " + operation + " is unavailable");
    }

    private static String encodeId(long rowId) {
        return "ms." + rowId;
    }

    private static long parseId(String value) {
        if (value == null || !value.startsWith("ms.")) {
            throw new IllegalArgumentException("Invalid MediaStore item ID");
        }
        try {
            long id = Long.parseLong(value.substring(3));
            if (id < 0) {
                throw new IllegalArgumentException("Invalid MediaStore item ID");
            }
            return id;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid MediaStore item ID", failure);
        }
    }

    private static String getString(Cursor cursor, String column, String fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getString(index);
    }

    private static long getLong(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getLong(index);
    }
}
