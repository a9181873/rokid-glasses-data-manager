package tw.dky.rokidfiles.storage;

/** A bounded, offset-based page request. */
public final class MediaQuery {
    public static final int DEFAULT_PAGE_SIZE = 48;
    public static final int MAX_PAGE_SIZE = 200;

    private final String parentId;
    private final int offset;
    private final int limit;
    private final boolean trash;
    private final Filter filter;
    private final long largeFileThresholdBytes;

    public enum Filter {
        ALL,
        TODAY,
        PROTECTED,
        FAVORITES,
        LARGE_FILES,
        DUPLICATES
    }

    public MediaQuery(String parentId, int offset, int limit, boolean trash) {
        this(parentId, offset, limit, trash, Filter.ALL, 500L * 1024L * 1024L);
    }

    public MediaQuery(
            String parentId,
            int offset,
            int limit,
            boolean trash,
            Filter filter,
            long largeFileThresholdBytes) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (largeFileThresholdBytes < 0L) {
            throw new IllegalArgumentException("largeFileThresholdBytes must be >= 0");
        }
        this.parentId = parentId;
        this.offset = offset;
        this.limit = limit;
        this.trash = trash;
        this.filter = filter == null ? Filter.ALL : filter;
        this.largeFileThresholdBytes = largeFileThresholdBytes;
    }

    public static MediaQuery root() {
        return new MediaQuery(null, 0, DEFAULT_PAGE_SIZE, false);
    }

    public static MediaQuery trash(int offset, int limit) {
        return new MediaQuery(null, offset, limit, true);
    }

    public String getParentId() {
        return parentId;
    }

    public int getOffset() {
        return offset;
    }

    public int getLimit() {
        return limit;
    }

    public boolean isTrash() {
        return trash;
    }

    public Filter getFilter() {
        return filter;
    }

    public long getLargeFileThresholdBytes() {
        return largeFileThresholdBytes;
    }

    MediaQuery withoutFilter(int rawOffset, int rawLimit) {
        return new MediaQuery(parentId, rawOffset, rawLimit, trash, Filter.ALL,
                largeFileThresholdBytes);
    }
}
