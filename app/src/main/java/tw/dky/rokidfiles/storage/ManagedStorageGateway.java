package tw.dky.rokidfiles.storage;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;

/** Adds app-private flags, protected-item enforcement and filtered views to any backend. */
public final class ManagedStorageGateway implements StorageGateway {
    private final StorageGateway delegate;
    private final MediaMetadataStore metadata;

    public ManagedStorageGateway(Context context, StorageGateway delegate) {
        this(delegate, new MediaMetadataStore(context));
    }

    public ManagedStorageGateway(StorageGateway delegate, MediaMetadataStore metadata) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    public StorageGateway getDelegate() {
        return delegate;
    }

    public MediaMetadataStore getMetadataStore() {
        return metadata;
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public MediaItem.Backend getBackend() {
        return delegate.getBackend();
    }

    @Override
    public StorageCapacity getCapacitySummary() throws IOException {
        return delegate.getCapacitySummary();
    }

    @Override
    public MediaPage list(MediaQuery query) throws IOException, SecurityException {
        Objects.requireNonNull(query, "query");
        if (query.getFilter() == MediaQuery.Filter.ALL) {
            MediaPage raw = delegate.list(query.withoutFilter(query.getOffset(), query.getLimit()));
            return new MediaPage(decorate(raw.getItems()), raw.getNextOffset(), raw.hasMore());
        }

        List<MediaItem> result = new ArrayList<>(query.getLimit());
        int rawOffset = 0;
        int matchingOffset = 0;
        boolean hasMoreMatches = false;
        long todayStart = startOfTodayMillis();
        while (true) {
            MediaPage raw = delegate.list(query.withoutFilter(
                    rawOffset, MediaQuery.MAX_PAGE_SIZE));
            for (MediaItem undecorated : raw.getItems()) {
                MediaItem item = metadata.decorate(undecorated);
                if (!matches(item, query, todayStart)) {
                    continue;
                }
                if (matchingOffset++ < query.getOffset()) {
                    continue;
                }
                if (result.size() >= query.getLimit()) {
                    hasMoreMatches = true;
                    break;
                }
                result.add(item);
            }
            if (hasMoreMatches || !raw.hasMore()) {
                break;
            }
            int next = raw.getNextOffset();
            if (next <= rawOffset) {
                break;
            }
            rawOffset = next;
        }
        return new MediaPage(
                result, query.getOffset() + result.size(), hasMoreMatches);
    }

    @Override
    public MediaItem getItem(String id) throws IOException, SecurityException {
        return metadata.decorate(delegate.getItem(id));
    }

    @Override
    public InputStream openInputStream(String id) throws IOException, SecurityException {
        return delegate.openInputStream(id);
    }

    @Override
    public AssetFileDescriptor openAssetFileDescriptor(String id)
            throws IOException, SecurityException {
        return delegate.openAssetFileDescriptor(id);
    }

    @Override
    public Bitmap loadThumbnail(String id, int width, int height)
            throws IOException, SecurityException {
        return delegate.loadThumbnail(id, width, height);
    }

    @Override
    public StorageOperationResult rename(String id, String newDisplayName) {
        MediaItem before;
        try {
            before = getItem(id);
        } catch (IOException | RuntimeException failure) {
            return readFailure(failure);
        }
        if (before.isProtected()) {
            return protectedResult();
        }
        StorageOperationResult result = delegate.rename(id, newDisplayName);
        return migrateAndDecorate(before, result);
    }

    @Override
    public StorageOperationResult moveToTrash(String id) {
        MediaItem before;
        try {
            before = getItem(id);
        } catch (IOException | RuntimeException failure) {
            return readFailure(failure);
        }
        if (before.isProtected()) {
            return protectedResult();
        }
        StorageOperationResult result = delegate.moveToTrash(id);
        return migrateAndDecorate(before, result);
    }

    @Override
    public StorageOperationResult restore(String id) {
        MediaItem before;
        try {
            before = getItem(id);
        } catch (IOException | RuntimeException failure) {
            return readFailure(failure);
        }
        return migrateAndDecorate(before, delegate.restore(id));
    }

    @Override
    public StorageOperationResult publishUpload(
            String parentId,
            String displayName,
            String mimeType,
            InputStream source,
            long contentLength) {
        StorageOperationResult result = delegate.publishUpload(
                parentId, displayName, mimeType, source, contentLength);
        if (result.isSuccess() && result.getItem() != null) {
            return StorageOperationResult.success(metadata.decorate(result.getItem()));
        }
        return result;
    }

    public boolean setFavorite(MediaItem item, boolean favorite) {
        return metadata.setFavorite(item, favorite);
    }

    public boolean setProtected(MediaItem item, boolean protectedItem) {
        return metadata.setProtected(item, protectedItem);
    }

    private StorageOperationResult migrateAndDecorate(
            MediaItem before, StorageOperationResult result) {
        if (!result.isSuccess() || result.getItem() == null) {
            return result;
        }
        metadata.migrate(before, result.getItem());
        return StorageOperationResult.success(metadata.decorate(result.getItem()));
    }

    private static List<MediaItem> decorateItems(
            List<MediaItem> source, MediaMetadataStore metadata) {
        List<MediaItem> result = new ArrayList<>(source.size());
        for (MediaItem item : source) {
            result.add(metadata.decorate(item));
        }
        return result;
    }

    private List<MediaItem> decorate(List<MediaItem> source) {
        return decorateItems(source, metadata);
    }

    private static boolean matches(MediaItem item, MediaQuery query, long todayStart) {
        if (item.getKind() == MediaItem.Kind.DIRECTORY) {
            return false;
        }
        switch (query.getFilter()) {
            case TODAY:
                return item.getLastModifiedMillis() >= todayStart;
            case PROTECTED:
                return item.isProtected();
            case FAVORITES:
                return item.isFavorite();
            case LARGE_FILES:
                return item.getSizeBytes() >= query.getLargeFileThresholdBytes();
            case DUPLICATES:
                return item.getDuplicateGroup() != null;
            case ALL:
            default:
                return true;
        }
    }

    private static long startOfTodayMillis() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static StorageOperationResult protectedResult() {
        return StorageOperationResult.failure(
                StorageOperationResult.Status.PROTECTED,
                "Protected items must be unprotected before rename or trash");
    }

    private static StorageOperationResult readFailure(Throwable failure) {
        StorageOperationResult.Status status = failure instanceof SecurityException
                ? StorageOperationResult.Status.PERMISSION_DENIED
                : StorageOperationResult.Status.IO_ERROR;
        String message = failure.getMessage();
        return StorageOperationResult.failure(status,
                message == null ? failure.getClass().getSimpleName() : message);
    }
}
