package tw.dky.rokidfiles.share;

import static org.junit.Assert.assertEquals;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tw.dky.rokidfiles.storage.MediaItem;
import tw.dky.rokidfiles.storage.MediaPage;
import tw.dky.rokidfiles.storage.MediaQuery;
import tw.dky.rokidfiles.storage.StorageGateway;
import tw.dky.rokidfiles.storage.StorageOperationResult;

public final class GatewayMediaAccessTest {
    @Test
    public void listMediaStopsAtTheShareLayerItemLimit() throws Exception {
        List<MediaItem> items = new ArrayList<>();
        for (int index = 0; index < 10_001; index++) {
            items.add(new MediaItem(
                    "id-" + index,
                    null,
                    null,
                    "photo-" + index + ".jpg",
                    "image/jpeg",
                    MediaItem.Kind.IMAGE,
                    MediaItem.Backend.MEDIA_STORE,
                    1L,
                    1L,
                    "Pictures/",
                    false,
                    MediaItem.CAPABILITY_READ));
        }

        GatewayMediaAccess access = new GatewayMediaAccess(new ListingGateway(items));

        assertEquals(10_000, access.listMedia().size());
    }

    private static final class ListingGateway implements StorageGateway {
        private final List<MediaItem> items;

        ListingGateway(List<MediaItem> items) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public MediaItem.Backend getBackend() {
            return MediaItem.Backend.MEDIA_STORE;
        }

        @Override
        public MediaPage list(MediaQuery query) {
            return new MediaPage(items, items.size(), false);
        }

        @Override
        public MediaItem getItem(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream openInputStream(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AssetFileDescriptor openAssetFileDescriptor(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Bitmap loadThumbnail(String id, int width, int height) {
            throw new UnsupportedOperationException();
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
