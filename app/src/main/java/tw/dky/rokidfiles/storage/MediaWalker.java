package tw.dky.rokidfiles.storage;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Memory-bounded breadth-first traversal shared by analysis jobs. */
final class MediaWalker {
    interface Visitor {
        void visit(MediaItem item) throws IOException;
    }

    private MediaWalker() {
    }

    static boolean walk(
            StorageGateway gateway,
            int maxDirectories,
            CancellationToken cancellation,
            Visitor visitor) throws IOException {
        ArrayDeque<DirectoryNode> pending = new ArrayDeque<>();
        pending.add(new DirectoryNode(null));
        Set<String> visited = new HashSet<>();
        Set<String> visitedFiles = new HashSet<>();
        visited.add("<root>");
        int directoryCount = 0;

        while (!pending.isEmpty()) {
            if (cancellation.isCancelled()) {
                return false;
            }
            DirectoryNode directory = pending.removeFirst();
            if (++directoryCount > maxDirectories) {
                throw new IOException("Directory traversal limit exceeded");
            }
            int offset = 0;
            while (true) {
                if (cancellation.isCancelled()) {
                    return false;
                }
                MediaPage page = gateway.list(new MediaQuery(
                        directory.id,
                        offset,
                        MediaQuery.MAX_PAGE_SIZE,
                        false,
                        MediaQuery.Filter.ALL,
                        0L));
                for (MediaItem item : page.getItems()) {
                    if (cancellation.isCancelled()) {
                        return false;
                    }
                    if (item.getKind() == MediaItem.Kind.DIRECTORY) {
                        if (visited.add(item.getId())) {
                            pending.addLast(new DirectoryNode(item.getId()));
                        }
                    } else if (visitedFiles.add(item.getId())) {
                        visitor.visit(item);
                    }
                }
                if (!page.hasMore()) {
                    break;
                }
                int next = page.getNextOffset();
                if (next <= offset) {
                    throw new IOException("Storage backend returned a non-advancing page");
                }
                offset = next;
            }
        }
        return true;
    }

    private static final class DirectoryNode {
        final String id;

        DirectoryNode(String id) {
            this.id = id;
        }
    }
}
