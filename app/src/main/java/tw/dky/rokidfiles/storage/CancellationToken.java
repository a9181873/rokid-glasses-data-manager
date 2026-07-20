package tw.dky.rokidfiles.storage;

/** Thread-safe cancellation hook for long scans. */
public interface CancellationToken {
    CancellationToken NONE = () -> false;

    boolean isCancelled();
}
