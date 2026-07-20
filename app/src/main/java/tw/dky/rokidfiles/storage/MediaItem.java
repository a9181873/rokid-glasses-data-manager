package tw.dky.rokidfiles.storage;

import android.net.Uri;

import java.util.Objects;

/**
 * Immutable metadata returned by a {@link StorageGateway}.
 *
 * <p>{@code id} and {@code parentId} are backend-owned opaque tokens. Callers must never parse
 * them or turn them into filesystem paths.</p>
 */
public final class MediaItem {
    public static final int CAPABILITY_READ = 1;
    public static final int CAPABILITY_THUMBNAIL = 1 << 1;
    public static final int CAPABILITY_RENAME = 1 << 2;
    public static final int CAPABILITY_TRASH = 1 << 3;
    public static final int CAPABILITY_RESTORE = 1 << 4;
    public static final int CAPABILITY_UPLOAD_CHILD = 1 << 5;

    public enum Kind {
        DIRECTORY,
        IMAGE,
        VIDEO
    }

    public enum Backend {
        MEDIA_STORE,
        ADVANCED_DIRECT
    }

    private final String id;
    private final String parentId;
    private final Uri uri;
    private final String displayName;
    private final String mimeType;
    private final Kind kind;
    private final Backend backend;
    private final long sizeBytes;
    private final long lastModifiedMillis;
    private final String relativePath;
    private final boolean trashed;
    private final int capabilities;
    private final boolean favorite;
    private final boolean protectedItem;
    private final String duplicateGroup;

    public MediaItem(
            String id,
            String parentId,
            Uri uri,
            String displayName,
            String mimeType,
            Kind kind,
            Backend backend,
            long sizeBytes,
            long lastModifiedMillis,
            String relativePath,
            boolean trashed,
            int capabilities) {
        this(id, parentId, uri, displayName, mimeType, kind, backend, sizeBytes,
                lastModifiedMillis, relativePath, trashed, capabilities, false, false, null);
    }

    public MediaItem(
            String id,
            String parentId,
            Uri uri,
            String displayName,
            String mimeType,
            Kind kind,
            Backend backend,
            long sizeBytes,
            long lastModifiedMillis,
            String relativePath,
            boolean trashed,
            int capabilities,
            boolean favorite,
            boolean protectedItem,
            String duplicateGroup) {
        this.id = Objects.requireNonNull(id, "id");
        this.parentId = parentId;
        this.uri = Objects.requireNonNull(uri, "uri");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.sizeBytes = sizeBytes;
        this.lastModifiedMillis = lastModifiedMillis;
        this.relativePath = relativePath;
        this.trashed = trashed;
        this.capabilities = capabilities;
        this.favorite = favorite;
        this.protectedItem = protectedItem;
        this.duplicateGroup = duplicateGroup;
    }

    public String getId() {
        return id;
    }

    public String getParentId() {
        return parentId;
    }

    public Uri getUri() {
        return uri;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Kind getKind() {
        return kind;
    }

    public Backend getBackend() {
        return backend;
    }

    /** Returns -1 when the provider did not report a size. */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** Returns Unix epoch milliseconds, or 0 when unknown. */
    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public boolean isTrashed() {
        return trashed;
    }

    public int getCapabilities() {
        return capabilities;
    }

    public boolean hasCapability(int capability) {
        return (capabilities & capability) == capability;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public boolean isProtected() {
        return protectedItem;
    }

    /** Stable SHA-256-derived group ID after a duplicate scan, or null when not grouped. */
    public String getDuplicateGroup() {
        return duplicateGroup;
    }

    MediaItem withUserMetadata(boolean favorite, boolean protectedItem, String duplicateGroup) {
        int effectiveCapabilities = capabilities;
        if (protectedItem) {
            effectiveCapabilities &= ~(CAPABILITY_RENAME | CAPABILITY_TRASH);
        }
        return new MediaItem(
                id,
                parentId,
                uri,
                displayName,
                mimeType,
                kind,
                backend,
                sizeBytes,
                lastModifiedMillis,
                relativePath,
                trashed,
                effectiveCapabilities,
                favorite,
                protectedItem,
                duplicateGroup);
    }
}
