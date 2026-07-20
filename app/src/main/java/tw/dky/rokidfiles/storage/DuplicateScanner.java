package tw.dky.rokidfiles.storage;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Cancellable two-pass duplicate detector: size counts first, then streaming SHA-256. */
public final class DuplicateScanner {
    public enum Phase {
        DISCOVERING,
        HASHING,
        COMPLETE
    }

    public interface ProgressListener {
        void onProgress(Progress progress);
    }

    public static final class Options {
        private final long minimumBytes;
        private final long maximumBytes;
        private final int maxDirectories;

        public Options(long minimumBytes, long maximumBytes, int maxDirectories) {
            if (minimumBytes < 0L || maximumBytes < minimumBytes || maxDirectories < 1) {
                throw new IllegalArgumentException("Invalid duplicate scan limits");
            }
            this.minimumBytes = minimumBytes;
            this.maximumBytes = maximumBytes;
            this.maxDirectories = maxDirectories;
        }

        public static Options defaults() {
            return new Options(1L, Long.MAX_VALUE, 10_000);
        }

        public long getMinimumBytes() {
            return minimumBytes;
        }

        public long getMaximumBytes() {
            return maximumBytes;
        }

        public int getMaxDirectories() {
            return maxDirectories;
        }
    }

    public static final class Progress {
        private final Phase phase;
        private final long filesProcessed;
        private final long candidateFiles;
        private final long bytesProcessed;
        private final long candidateBytes;

        Progress(
                Phase phase,
                long filesProcessed,
                long candidateFiles,
                long bytesProcessed,
                long candidateBytes) {
            this.phase = phase;
            this.filesProcessed = filesProcessed;
            this.candidateFiles = candidateFiles;
            this.bytesProcessed = bytesProcessed;
            this.candidateBytes = candidateBytes;
        }

        public Phase getPhase() {
            return phase;
        }

        public long getFilesProcessed() {
            return filesProcessed;
        }

        public long getCandidateFiles() {
            return candidateFiles;
        }

        public long getBytesProcessed() {
            return bytesProcessed;
        }

        public long getCandidateBytes() {
            return candidateBytes;
        }
    }

    public static final class Group {
        private final String id;
        private final long fileSizeBytes;
        private final List<MediaItem> items;

        Group(String id, long fileSizeBytes, List<MediaItem> items) {
            this.id = id;
            this.fileSizeBytes = fileSizeBytes;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        public String getId() {
            return id;
        }

        public long getFileSizeBytes() {
            return fileSizeBytes;
        }

        public List<MediaItem> getItems() {
            return items;
        }

        public long getRecoverableBytes() {
            return saturatedMultiply(fileSizeBytes, Math.max(0L, items.size() - 1L));
        }
    }

    public static final class Result {
        private final boolean cancelled;
        private final List<Group> groups;
        private final long filesDiscovered;
        private final long filesHashed;
        private final long filesSkipped;

        Result(
                boolean cancelled,
                List<Group> groups,
                long filesDiscovered,
                long filesHashed,
                long filesSkipped) {
            this.cancelled = cancelled;
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
            this.filesDiscovered = filesDiscovered;
            this.filesHashed = filesHashed;
            this.filesSkipped = filesSkipped;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public List<Group> getGroups() {
            return groups;
        }

        public long getFilesDiscovered() {
            return filesDiscovered;
        }

        public long getFilesHashed() {
            return filesHashed;
        }

        public long getFilesSkipped() {
            return filesSkipped;
        }
    }

    private final StorageGateway gateway;
    private final MediaMetadataStore metadata;

    public DuplicateScanner(StorageGateway gateway) {
        this(gateway,
                gateway instanceof ManagedStorageGateway
                        ? ((ManagedStorageGateway) gateway).getMetadataStore()
                        : null);
    }

    public DuplicateScanner(StorageGateway gateway, MediaMetadataStore metadata) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.metadata = metadata;
    }

    /** Run on a worker thread; callers commonly gate invocation on charging state. */
    public Result scan(
            Options options,
            CancellationToken cancellation,
            ProgressListener listener) throws IOException {
        Objects.requireNonNull(options, "options");
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        ProgressListener progress = listener == null ? ignored -> { } : listener;

        Map<Long, Integer> sizeCounts = new HashMap<>();
        long[] discovered = {0L};
        boolean complete = MediaWalker.walk(
                gateway,
                options.maxDirectories,
                token,
                item -> {
                    long size = item.getSizeBytes();
                    if (size >= options.minimumBytes && size <= options.maximumBytes) {
                        sizeCounts.merge(size, 1, Integer::sum);
                    }
                    discovered[0]++;
                    if ((discovered[0] & 63L) == 0L) {
                        progress.onProgress(new Progress(
                                Phase.DISCOVERING, discovered[0], 0L, 0L, 0L));
                    }
                });
        if (!complete) {
            return new Result(true, Collections.emptyList(), discovered[0], 0L, 0L);
        }

        long candidateFiles = 0L;
        long candidateBytes = 0L;
        for (Map.Entry<Long, Integer> entry : sizeCounts.entrySet()) {
            if (entry.getValue() > 1) {
                candidateFiles = saturatedAdd(candidateFiles, entry.getValue());
                candidateBytes = saturatedAdd(
                        candidateBytes, saturatedMultiply(entry.getKey(), entry.getValue()));
            }
        }
        final long totalCandidateFiles = candidateFiles;
        final long totalCandidateBytes = candidateBytes;
        progress.onProgress(new Progress(
                Phase.HASHING, 0L, candidateFiles, 0L, candidateBytes));

        Map<String, List<MediaItem>> byDigest = new LinkedHashMap<>();
        long[] hashed = {0L};
        long[] skipped = {0L};
        long[] bytesProcessed = {0L};
        byte[] buffer = new byte[StorageSupport.COPY_BUFFER_BYTES];
        MessageDigest digest = sha256();
        complete = MediaWalker.walk(
                gateway,
                options.maxDirectories,
                token,
                item -> {
                    long size = item.getSizeBytes();
                    Integer count = sizeCounts.get(size);
                    if (count == null || count < 2) {
                        return;
                    }
                    digest.reset();
                    long readTotal = 0L;
                    long nextProgressBytes = saturatedAdd(
                            bytesProcessed[0], 4L * 1024L * 1024L);
                    try (InputStream stream = gateway.openInputStream(item.getId())) {
                        int read;
                        while ((read = stream.read(buffer)) != -1) {
                            if (token.isCancelled()) {
                                return;
                            }
                            digest.update(buffer, 0, read);
                            readTotal += read;
                            bytesProcessed[0] = saturatedAdd(bytesProcessed[0], read);
                            if (bytesProcessed[0] >= nextProgressBytes) {
                                progress.onProgress(new Progress(
                                        Phase.HASHING,
                                        hashed[0] + skipped[0],
                                        totalCandidateFiles,
                                        bytesProcessed[0],
                                        totalCandidateBytes));
                                nextProgressBytes = saturatedAdd(
                                        bytesProcessed[0], 4L * 1024L * 1024L);
                            }
                        }
                        if (readTotal != size) {
                            skipped[0]++;
                            return;
                        }
                        String hex = toHex(digest.digest());
                        byDigest.computeIfAbsent(size + ":" + hex, ignored -> new ArrayList<>())
                                .add(item);
                        hashed[0]++;
                    } catch (IOException | SecurityException unreadable) {
                        skipped[0]++;
                    }
                    progress.onProgress(new Progress(
                            Phase.HASHING,
                            hashed[0] + skipped[0],
                            totalCandidateFiles,
                            bytesProcessed[0],
                            totalCandidateBytes));
                });
        if (!complete || token.isCancelled()) {
            return new Result(true, Collections.emptyList(),
                    discovered[0], hashed[0], skipped[0]);
        }

        List<Group> groups = new ArrayList<>();
        Map<MediaItem, String> assignments = new HashMap<>();
        for (Map.Entry<String, List<MediaItem>> entry : byDigest.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String digestHex = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            String groupId = "sha256:" + digestHex;
            List<MediaItem> grouped = new ArrayList<>(entry.getValue().size());
            for (MediaItem item : entry.getValue()) {
                assignments.put(item, groupId);
                grouped.add(item.withUserMetadata(
                        item.isFavorite(), item.isProtected(), groupId));
            }
            groups.add(new Group(groupId, entry.getValue().get(0).getSizeBytes(), grouped));
        }
        groups.sort(Comparator.comparingLong(Group::getRecoverableBytes).reversed());
        if (metadata != null) {
            metadata.replaceDuplicateGroups(assignments);
        }
        progress.onProgress(new Progress(
                Phase.COMPLETE, hashed[0] + skipped[0], candidateFiles,
                bytesProcessed[0], candidateBytes));
        return new Result(false, groups, discovered[0], hashed[0], skipped[0]);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Android must provide SHA-256", impossible);
        }
    }

    private static String toHex(byte[] value) {
        char[] hex = new char[value.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            int current = value[i] & 0xff;
            hex[i * 2] = alphabet[current >>> 4];
            hex[i * 2 + 1] = alphabet[current & 0x0f];
        }
        return new String(hex);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left > 0L && right > Long.MAX_VALUE / left) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }
}
