package tw.dky.rokidfiles.storage;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Pure-Java, bounded cleanup for unpublished upload files. */
final class StaleUploadCleaner {
    static final long MINIMUM_AGE_MILLIS = TimeUnit.HOURS.toMillis(24L);
    static final int MAX_SCANNED_ENTRIES = 4_096;
    static final int MAX_DELETIONS = 128;

    private static final String TRASH_DIRECTORY = ".RokidFilesTrash";
    private static final Pattern SAFE_PART_NAME = Pattern.compile(
            "\\A\\.rokid-upload-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.part\\z");

    private StaleUploadCleaner() {
    }

    /**
     * Scans only the supplied roots. All I/O and concurrent-change failures are isolated so this
     * method is safe to call during gateway startup.
     *
     * @return number of entries deleted during this bounded pass
     */
    static int clean(List<Path> allowlistedRoots, Set<Path> activeUploads, long nowMillis) {
        if (allowlistedRoots == null || activeUploads == null) {
            return 0;
        }
        long cutoffMillis = nowMillis < Long.MIN_VALUE + MINIMUM_AGE_MILLIS
                ? Long.MIN_VALUE : nowMillis - MINIMUM_AGE_MILLIS;
        Budget budget = new Budget();
        for (Path root : allowlistedRoots) {
            if (budget.exhausted()) {
                break;
            }
            try {
                cleanRoot(root, activeUploads, cutoffMillis, budget);
            } catch (IOException | RuntimeException ignored) {
                // One inaccessible or concurrently changed root must not abort other roots.
            }
        }
        return budget.deleted;
    }

    static boolean hasSafePartName(String name) {
        return name != null && SAFE_PART_NAME.matcher(name).matches();
    }

    private static void cleanRoot(
            Path suppliedRoot,
            Set<Path> activeUploads,
            long cutoffMillis,
            Budget budget) throws IOException {
        if (suppliedRoot == null) {
            return;
        }
        Path root = normalize(suppliedRoot);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!budget.recordEntry()) {
                    return FileVisitResult.TERMINATE;
                }
                Path normalized = normalize(directory);
                if (!normalized.startsWith(root)
                        || attributes.isSymbolicLink()
                        || containsSymbolicLink(root, normalized)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!normalized.equals(root)
                        && TRASH_DIRECTORY.equals(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!budget.recordEntry()) {
                    return FileVisitResult.TERMINATE;
                }
                if (!budget.deletionLimitReached()) {
                    tryDelete(root, file, attributes, activeUploads, cutoffMillis, budget);
                }
                return budget.exhausted()
                        ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                return budget.recordEntry()
                        ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
            }
        });
    }

    private static void tryDelete(
            Path root,
            Path file,
            BasicFileAttributes visited,
            Set<Path> activeUploads,
            long cutoffMillis,
            Budget budget) {
        try {
            Path normalized = normalize(file);
            if (!normalized.startsWith(root)
                    || !hasSafePartName(file.getFileName().toString())
                    || activeUploads.contains(normalized)
                    || visited.isSymbolicLink()
                    || !visited.isRegularFile()
                    || visited.lastModifiedTime().toMillis() > cutoffMillis
                    || containsSymbolicLink(root, normalized)) {
                return;
            }

            BasicFileAttributes current = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (current.isSymbolicLink()
                    || !current.isRegularFile()
                    || current.lastModifiedTime().toMillis() > cutoffMillis
                    || !sameSnapshot(visited, current)
                    || containsSymbolicLink(root, normalized)
                    || activeUploads.contains(normalized)) {
                return;
            }
            if (Files.deleteIfExists(file)) {
                budget.deleted++;
            }
        } catch (IOException | RuntimeException ignored) {
            // Per-file races and permission failures are intentionally non-fatal.
        }
    }

    private static boolean containsSymbolicLink(Path root, Path candidate) {
        if (!candidate.startsWith(root) || Files.isSymbolicLink(root)) {
            return true;
        }
        Path current = root;
        for (Path component : root.relativize(candidate)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameSnapshot(BasicFileAttributes first, BasicFileAttributes second) {
        Object firstKey = first.fileKey();
        Object secondKey = second.fileKey();
        if (firstKey != null || secondKey != null) {
            return firstKey != null && firstKey.equals(secondKey);
        }
        return first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static final class Budget {
        int scanned;
        int deleted;

        boolean recordEntry() {
            if (scanned >= MAX_SCANNED_ENTRIES) {
                return false;
            }
            scanned++;
            return true;
        }

        boolean deletionLimitReached() {
            return deleted >= MAX_DELETIONS;
        }

        boolean exhausted() {
            return scanned >= MAX_SCANNED_ENTRIES || deletionLimitReached();
        }
    }
}
