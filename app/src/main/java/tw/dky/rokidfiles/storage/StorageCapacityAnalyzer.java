package tw.dky.rokidfiles.storage;

import android.content.res.AssetFileDescriptor;
import android.media.MediaMetadataRetriever;

import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

/** Adds a remaining-recording-time estimate based on a small set of recent videos. */
public final class StorageCapacityAnalyzer {
    private StorageCapacityAnalyzer() {
    }

    public static StorageCapacity analyze(
            StorageGateway gateway,
            int recentVideoSamples,
            int maxDirectories,
            CancellationToken cancellation) throws IOException {
        Objects.requireNonNull(gateway, "gateway");
        if (recentVideoSamples < 1 || recentVideoSamples > 32 || maxDirectories < 1) {
            throw new IllegalArgumentException("Invalid capacity analysis limits");
        }
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        StorageCapacity basic = gateway.getCapacitySummary();
        PriorityQueue<MediaItem> recent = new PriorityQueue<>(
                recentVideoSamples,
                Comparator.comparingLong(MediaItem::getLastModifiedMillis));
        boolean complete = MediaWalker.walk(
                gateway,
                maxDirectories,
                token,
                item -> {
                    if (item.getKind() != MediaItem.Kind.VIDEO || item.getSizeBytes() <= 0L) {
                        return;
                    }
                    recent.offer(item);
                    if (recent.size() > recentVideoSamples) {
                        recent.poll();
                    }
                });
        if (!complete) {
            return basic;
        }

        double totalBits = 0.0;
        double totalSeconds = 0.0;
        int usableSamples = 0;
        for (MediaItem item : recent) {
            if (token.isCancelled()) {
                return basic;
            }
            long durationMillis = readDurationMillis(gateway, item);
            if (durationMillis <= 0L) {
                continue;
            }
            totalBits += item.getSizeBytes() * 8.0;
            totalSeconds += durationMillis / 1000.0;
            usableSamples++;
        }
        if (usableSamples == 0 || totalSeconds <= 0.0) {
            return basic;
        }
        long averageBitrate = (long) Math.max(1.0, totalBits / totalSeconds);
        long remainingMinutes = StorageCapacity.UNKNOWN;
        if (basic.getFreeBytes() >= 0L) {
            double minutes = basic.getFreeBytes() * 8.0 / averageBitrate / 60.0;
            remainingMinutes = minutes >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) minutes;
        }
        return basic.withVideoEstimate(averageBitrate, remainingMinutes, usableSamples);
    }

    public static StorageCapacity analyzeDefault(StorageGateway gateway) throws IOException {
        return analyze(gateway, 8, 10_000, CancellationToken.NONE);
    }

    private static long readDurationMillis(StorageGateway gateway, MediaItem item) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (AssetFileDescriptor descriptor = gateway.openAssetFileDescriptor(item.getId())) {
            long length = descriptor.getDeclaredLength();
            if (length >= 0L) {
                retriever.setDataSource(
                        descriptor.getFileDescriptor(), descriptor.getStartOffset(), length);
            } else if (descriptor.getStartOffset() == 0L) {
                retriever.setDataSource(descriptor.getFileDescriptor());
            } else {
                return -1L;
            }
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration == null ? -1L : Long.parseLong(duration);
        } catch (IOException | RuntimeException unavailable) {
            return -1L;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // Metadata extraction has already finished or failed.
            }
        }
    }
}
