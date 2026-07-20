package tw.dky.rokidfiles.storage;

/** Capacity values use -1 to mean that the backend/device cannot provide a reliable value. */
public final class StorageCapacity {
    public static final long UNKNOWN = -1L;

    private final long totalBytes;
    private final long freeBytes;
    private final long averageVideoBitrateBitsPerSecond;
    private final long remainingVideoMinutes;
    private final int videoSamples;

    public StorageCapacity(
            long totalBytes,
            long freeBytes,
            long averageVideoBitrateBitsPerSecond,
            long remainingVideoMinutes,
            int videoSamples) {
        this.totalBytes = normalize(totalBytes);
        this.freeBytes = normalize(freeBytes);
        this.averageVideoBitrateBitsPerSecond = normalize(averageVideoBitrateBitsPerSecond);
        this.remainingVideoMinutes = normalize(remainingVideoMinutes);
        this.videoSamples = Math.max(0, videoSamples);
    }

    public static StorageCapacity unknown() {
        return new StorageCapacity(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, 0);
    }

    public static StorageCapacity basic(long totalBytes, long freeBytes) {
        return new StorageCapacity(totalBytes, freeBytes, UNKNOWN, UNKNOWN, 0);
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getFreeBytes() {
        return freeBytes;
    }

    public long getAverageVideoBitrateBitsPerSecond() {
        return averageVideoBitrateBitsPerSecond;
    }

    public long getRemainingVideoMinutes() {
        return remainingVideoMinutes;
    }

    public int getVideoSamples() {
        return videoSamples;
    }

    public boolean hasVideoEstimate() {
        return averageVideoBitrateBitsPerSecond >= 0L && remainingVideoMinutes >= 0L;
    }

    StorageCapacity withVideoEstimate(long averageBitrate, long remainingMinutes, int samples) {
        return new StorageCapacity(totalBytes, freeBytes, averageBitrate, remainingMinutes, samples);
    }

    private static long normalize(long value) {
        return value < 0L ? UNKNOWN : value;
    }
}
