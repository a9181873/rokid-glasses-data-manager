package tw.dky.rokidfiles.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded page. The next offset is only valid when {@link #hasMore()} is true. */
public final class MediaPage {
    private final List<MediaItem> items;
    private final int nextOffset;
    private final boolean hasMore;

    public MediaPage(List<MediaItem> items, int nextOffset, boolean hasMore) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.nextOffset = nextOffset;
        this.hasMore = hasMore;
    }

    public List<MediaItem> getItems() {
        return items;
    }

    public int getNextOffset() {
        return nextOffset;
    }

    public boolean hasMore() {
        return hasMore;
    }
}
