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

/** Cancellable duplicate detector: size → 64 KiB prefix → streaming full SHA-256. */
public final class DuplicateScanner {
    /** 前段指紋只讀 64 KiB；不同者不再做昂貴的全檔 SHA-256。 */
    static final int PREFIX_HASH_BYTES = 64 * 1024;

    public enum Phase {
        DISCOVERING,
        PREFILTERING,
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

        long sizeCandidateFiles = 0L;
        long sizeCandidatePrefixBytes = 0L;
        for (Map.Entry<Long, Integer> entry : sizeCounts.entrySet()) {
            if (entry.getValue() > 1) {
                sizeCandidateFiles = saturatedAdd(sizeCandidateFiles, entry.getValue());
                sizeCandidatePrefixBytes = saturatedAdd(
                        sizeCandidatePrefixBytes,
                        saturatedMultiply(
                                Math.min(entry.getKey(), PREFIX_HASH_BYTES), entry.getValue()));
            }
        }
        final long totalSizeCandidateFiles = sizeCandidateFiles;
        final long totalPrefixBytes = sizeCandidatePrefixBytes;
        progress.onProgress(new Progress(
                Phase.PREFILTERING, 0L, sizeCandidateFiles, 0L, sizeCandidatePrefixBytes));

        // 第二次目錄走訪只讀同尺寸候選的前 64 KiB；前段不同者不再讀完整檔案。
        Map<String, List<MediaItem>> byPrefix = new LinkedHashMap<>();
        long[] prefixProcessed = {0L};
        long[] prefixBytesProcessed = {0L};
        long[] skipped = {0L};
        byte[] prefixBuffer = new byte[Math.min(
                PREFIX_HASH_BYTES, StorageSupport.COPY_BUFFER_BYTES)];
        MessageDigest prefixDigest = sha256();
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
                    try (InputStream stream = gateway.openInputStream(item.getId())) {
                        String prefixHex = hashPrefix(stream, prefixDigest, prefixBuffer);
                        byPrefix.computeIfAbsent(
                                        size + ":" + prefixHex, ignored -> new ArrayList<>())
                                .add(item);
                    } catch (IOException | SecurityException unreadable) {
                        skipped[0]++;
                    }
                    prefixProcessed[0]++;
                    prefixBytesProcessed[0] = saturatedAdd(
                            prefixBytesProcessed[0], Math.min(size, PREFIX_HASH_BYTES));
                    progress.onProgress(new Progress(
                            Phase.PREFILTERING,
                            prefixProcessed[0],
                            totalSizeCandidateFiles,
                            prefixBytesProcessed[0],
                            totalPrefixBytes));
                });
        if (!complete || token.isCancelled()) {
            return new Result(true, Collections.emptyList(),
                    discovered[0], 0L, skipped[0]);
        }

        List<MediaItem> fullCandidates = new ArrayList<>();
        long fullCandidateBytes = 0L;
        for (List<MediaItem> candidates : byPrefix.values()) {
            if (candidates.size() < 2) {
                continue;
            }
            fullCandidates.addAll(candidates);
            for (MediaItem item : candidates) {
                fullCandidateBytes = saturatedAdd(fullCandidateBytes, item.getSizeBytes());
            }
        }
        final long totalFullCandidateFiles = fullCandidates.size();
        final long totalFullCandidateBytes = fullCandidateBytes;
        progress.onProgress(new Progress(
                Phase.HASHING, 0L, totalFullCandidateFiles, 0L, totalFullCandidateBytes));

        Map<String, List<MediaItem>> byDigest = new LinkedHashMap<>();
        long hashed = 0L;
        long fullSkipped = 0L;
        long bytesProcessed = 0L;
        byte[] buffer = new byte[StorageSupport.COPY_BUFFER_BYTES];
        MessageDigest digest = sha256();
        for (MediaItem item : fullCandidates) {
            if (token.isCancelled()) {
                return new Result(true, Collections.emptyList(),
                        discovered[0], hashed, saturatedAdd(skipped[0], fullSkipped));
            }
            digest.reset();
            long readTotal = 0L;
            long nextProgressBytes = saturatedAdd(bytesProcessed, 4L * 1024L * 1024L);
            try (InputStream stream = gateway.openInputStream(item.getId())) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (token.isCancelled()) {
                        return new Result(true, Collections.emptyList(),
                                discovered[0], hashed,
                                saturatedAdd(skipped[0], fullSkipped));
                    }
                    if (read == 0) {
                        int single = stream.read();
                        if (single < 0) {
                            break;
                        }
                        digest.update((byte) single);
                        readTotal++;
                        bytesProcessed = saturatedAdd(bytesProcessed, 1L);
                    } else {
                        digest.update(buffer, 0, read);
                        readTotal += read;
                        bytesProcessed = saturatedAdd(bytesProcessed, read);
                    }
                    if (bytesProcessed >= nextProgressBytes) {
                        progress.onProgress(new Progress(
                                Phase.HASHING,
                                hashed + fullSkipped,
                                totalFullCandidateFiles,
                                bytesProcessed,
                                totalFullCandidateBytes));
                        nextProgressBytes = saturatedAdd(
                                bytesProcessed, 4L * 1024L * 1024L);
                    }
                }
                if (readTotal != item.getSizeBytes()) {
                    fullSkipped++;
                } else {
                    String hex = toHex(digest.digest());
                    byDigest.computeIfAbsent(
                                    item.getSizeBytes() + ":" + hex,
                                    ignored -> new ArrayList<>())
                            .add(item);
                    hashed++;
                }
            } catch (IOException | SecurityException unreadable) {
                fullSkipped++;
            }
            progress.onProgress(new Progress(
                    Phase.HASHING,
                    hashed + fullSkipped,
                    totalFullCandidateFiles,
                    bytesProcessed,
                    totalFullCandidateBytes));
        }
        long totalSkipped = saturatedAdd(skipped[0], fullSkipped);

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
                Phase.COMPLETE, hashed + fullSkipped, totalFullCandidateFiles,
                bytesProcessed, totalFullCandidateBytes));
        return new Result(false, groups, discovered[0], hashed, totalSkipped);
    }

    /** Package-private for a deterministic unit test; reads no more than 64 KiB. */
    static String hashPrefix(InputStream stream) throws IOException {
        return hashPrefix(
                stream,
                sha256(),
                new byte[Math.min(PREFIX_HASH_BYTES, StorageSupport.COPY_BUFFER_BYTES)]);
    }

    private static String hashPrefix(
            InputStream stream,
            MessageDigest digest,
            byte[] buffer) throws IOException {
        digest.reset();
        int remaining = PREFIX_HASH_BYTES;
        while (remaining > 0) {
            int read = stream.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            if (read == 0) {
                int single = stream.read();
                if (single < 0) {
                    break;
                }
                digest.update((byte) single);
                remaining--;
                continue;
            }
            digest.update(buffer, 0, read);
            remaining -= read;
        }
        return toHex(digest.digest());
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
