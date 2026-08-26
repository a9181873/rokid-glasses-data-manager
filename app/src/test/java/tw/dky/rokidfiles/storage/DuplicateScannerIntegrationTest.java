package tw.dky.rokidfiles.storage;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.net.Uri;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class DuplicateScannerIntegrationTest {
    @Test
    public void differentPrefixesAvoidFullFileHashing() throws IOException {
        int size = DuplicateScanner.PREFIX_HASH_BYTES * 4;
        byte[] left = new byte[size];
        byte[] right = new byte[size];
        left[0] = 1;
        right[0] = 2;
        CountingGateway gateway = new CountingGateway();
        gateway.add("left", left);
        gateway.add("right", right);

        DuplicateScanner.Result result = new DuplicateScanner(gateway).scan(
                DuplicateScanner.Options.defaults(), CancellationToken.NONE, null);

        assertFalse(result.isCancelled());
        assertEquals(0, result.getGroups().size());
        assertEquals(0, result.getFilesHashed());
        assertEquals(2L * DuplicateScanner.PREFIX_HASH_BYTES, gateway.bytesRead);
    }

    private static final class CountingGateway implements StorageGateway {
        private final Map<String, byte[]> content = new LinkedHashMap<>();
        private final Map<String, MediaItem> items = new LinkedHashMap<>();
        long bytesRead;

        void add(String id, byte[] bytes) {
            content.put(id, bytes);
            items.put(id, new MediaItem(
                    id,
                    null,
                    Uri.EMPTY,
                    id + ".jpg",
                    "image/jpeg",
                    MediaItem.Kind.IMAGE,
                    MediaItem.Backend.ADVANCED_DIRECT,
                    bytes.length,
                    1L,
                    "DCIM/Camera/" + id + ".jpg",
                    false,
                    MediaItem.CAPABILITY_READ));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public MediaItem.Backend getBackend() {
            return MediaItem.Backend.ADVANCED_DIRECT;
        }

        @Override
        public MediaPage list(MediaQuery query) {
            return new MediaPage(Arrays.asList(items.values().toArray(new MediaItem[0])), 0, false);
        }

        @Override
        public MediaItem getItem(String id) throws IOException {
            MediaItem item = items.get(id);
            if (item == null) {
                throw new IOException("missing test item");
            }
            return item;
        }

        @Override
        public InputStream openInputStream(String id) throws IOException {
            byte[] bytes = content.get(id);
            if (bytes == null) {
                throw new IOException("missing test content");
            }
            return new ByteArrayInputStream(bytes) {
                @Override
                public synchronized int read(byte[] buffer, int offset, int length) {
                    int count = super.read(buffer, offset, length);
                    if (count > 0) {
                        bytesRead += count;
                    }
                    return count;
                }

                @Override
                public synchronized int read() {
                    int value = super.read();
                    if (value >= 0) {
                        bytesRead++;
                    }
                    return value;
                }
            };
        }

        @Override
        public AssetFileDescriptor openAssetFileDescriptor(String id) {
            return null;
        }

        @Override
        public Bitmap loadThumbnail(String id, int width, int height) {
            return null;
        }

        @Override
        public StorageOperationResult rename(String id, String newDisplayName) {
            return StorageOperationResult.unsupported("test");
        }

        @Override
        public StorageOperationResult moveToTrash(String id) {
            return StorageOperationResult.unsupported("test");
        }

        @Override
        public StorageOperationResult restore(String id) {
            return StorageOperationResult.unsupported("test");
        }
    }
}
