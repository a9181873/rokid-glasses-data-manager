package tw.dky.rokidfiles.storage;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import java.io.IOException;
import java.io.InputStream;

/**
 * UI-independent media API shared by activities, background services and HTTP/share adapters.
 * All blocking calls must run off the main thread.
 */
public interface StorageGateway {
    boolean isAvailable();

    MediaItem.Backend getBackend();

    /** Fast capacity snapshot; video-time estimation is added by {@link StorageCapacityAnalyzer}. */
    default StorageCapacity getCapacitySummary() throws IOException {
        return StorageCapacity.unknown();
    }

    MediaPage list(MediaQuery query) throws IOException, SecurityException;

    MediaItem getItem(String id) throws IOException, SecurityException;

    InputStream openInputStream(String id) throws IOException, SecurityException;

    /** Caller owns and must close the returned descriptor. */
    AssetFileDescriptor openAssetFileDescriptor(String id) throws IOException, SecurityException;

    /**
     * Returns a bounded thumbnail or null when no thumbnail can be produced. Width and height are
     * capped internally, so this method never intentionally decodes a full-resolution image.
     */
    Bitmap loadThumbnail(String id, int width, int height) throws IOException, SecurityException;

    StorageOperationResult rename(String id, String newDisplayName);

    StorageOperationResult moveToTrash(String id);

    StorageOperationResult restore(String id);

    /**
     * Streams an upload into a temporary file/document and publishes it only after EOF. The caller
     * retains ownership of {@code source}. A negative content length means unknown.
     */
    default StorageOperationResult publishUpload(
            String parentId,
            String displayName,
            String mimeType,
            InputStream source,
            long contentLength) {
        return StorageOperationResult.unsupported("This backend does not support uploads");
    }
}
