package tw.dky.rokidfiles.storage;

/** Explicit result for mutations; unsupported provider features are never mistaken for I/O errors. */
public final class StorageOperationResult {
    public enum Status {
        SUCCESS,
        UNSUPPORTED,
        INVALID_INPUT,
        PROTECTED,
        NOT_FOUND,
        CONFLICT,
        PERMISSION_DENIED,
        IO_ERROR
    }

    private final Status status;
    private final String message;
    private final MediaItem item;

    private StorageOperationResult(Status status, String message, MediaItem item) {
        this.status = status;
        this.message = message == null ? "" : message;
        this.item = item;
    }

    public static StorageOperationResult success(MediaItem item) {
        return new StorageOperationResult(Status.SUCCESS, "", item);
    }

    public static StorageOperationResult unsupported(String message) {
        return new StorageOperationResult(Status.UNSUPPORTED, message, null);
    }

    public static StorageOperationResult failure(Status status, String message) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("Use success() for successful operations");
        }
        return new StorageOperationResult(status, message, null);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public MediaItem getItem() {
        return item;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
