package tw.dky.rokidfiles.share;

import android.content.Context;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 分享伺服器與實際媒體儲存區之間的唯一邊界。
 *
 * <p>實作端可以使用 MediaStore、DocumentProvider 或其他安全儲存層，但不可把路徑交給
 * HTTP 層。{@link MediaItem#getRepositoryId()} 只在程序內使用，分享伺服器會另行轉成每次
 * 啟動都不同的 opaque ID。</p>
 *
 * <p>伺服器可能同時呼叫讀取方法；實作必須為 thread-safe。所有回傳串流都會由伺服器
 * 關閉。上傳應採暫存檔／pending MediaStore item，只有完整讀完 {@code contentLength}
 * 才提交，例外時不得留下半成品。</p>
 */
public interface MediaAccess {
    /** 由 App 在啟動 ShareService 前安裝，以便程序重建時重新取得 repository。 */
    interface Provider {
        MediaAccess create(Context applicationContext) throws IOException;
    }

    /** 回傳目前可管理的照片與影片；不得包含非必要的私密欄位或實體路徑。 */
    List<MediaItem> listMedia() throws IOException;

    /**
     * 從指定偏移開啟原始媒體。回傳資源的 totalLength 必須是完整檔案長度，且串流第一個
     * byte 必須正好是 offset。實作若不支援 seek，仍需在 repository 內安全地略過資料。
     */
    ReadResource openMedia(String repositoryId, long offset) throws IOException;

    /**
     * 回傳預覽縮圖，找不到時可回傳 null。建議輸出 JPEG、PNG 或 WebP，長邊不超過
     * maxEdgePixels；已知長度不得超過伺服器的縮圖限制。
     */
    ReadResource openThumbnail(String repositoryId, int maxEdgePixels) throws IOException;

    /** 移至系統垃圾桶或 repository 的可復原垃圾桶，不應直接永久刪除。 */
    void moveToTrash(String repositoryId) throws IOException;

    /** 只接受單一顯示名稱；repository 必須再次檢查名稱及衝突。 */
    MediaItem renameMedia(String repositoryId, String newDisplayName) throws IOException;

    /**
     * 串流匯入一個檔案。displayName 已經過分享層基本檢查，仍不可把它當成路徑。
     * Repository 必須精確讀完 contentLength，並以原子方式提交。
     */
    MediaItem uploadMedia(
            String displayName,
            String mimeType,
            InputStream body,
            long contentLength
    ) throws IOException;

    /** 選用：列出垃圾桶內容；未實作時伺服器會回 HTTP 501。 */
    default List<MediaItem> listTrash() throws IOException {
        throw new Failure(Failure.Reason.UNSUPPORTED, "此儲存區不支援垃圾桶瀏覽");
    }

    /** 選用：從垃圾桶還原。 */
    default MediaItem restoreMedia(String repositoryId) throws IOException {
        throw new Failure(Failure.Reason.UNSUPPORTED, "此儲存區不支援還原");
    }

    /** 選用：切換最愛。回傳更新後的完整項目。 */
    default MediaItem setFavorite(String repositoryId, boolean favorite) throws IOException {
        throw new Failure(Failure.Reason.UNSUPPORTED, "此儲存區不支援最愛標記");
    }

    /** 選用：切換防誤刪保護。回傳更新後的完整項目。 */
    default MediaItem setProtected(String repositoryId, boolean protectedFromTrash)
            throws IOException {
        throw new Failure(Failure.Reason.UNSUPPORTED, "此儲存區不支援防誤刪保護");
    }

    /**
     * 選用：由 repository 以內容雜湊等可靠方式掃描重複檔；不得只靠檔名判斷。
     * 回傳值是此次掃描的真實快照，未實作請保留預設 501。
     */
    default List<DuplicateGroup> scanDuplicates() throws IOException {
        throw new Failure(Failure.Reason.UNSUPPORTED, "此儲存區不支援重複檔掃描");
    }

    enum Kind {
        PHOTO("photo"),
        VIDEO("video"),
        OTHER("other");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String getWireName() {
            return wireName;
        }
    }

    /** Repository 回傳的安全中繼資料。repositoryId 絕不會送到客戶端。 */
    final class MediaItem {
        private final String repositoryId;
        private final String displayName;
        private final String mimeType;
        private final Kind kind;
        private final long size;
        private final long modifiedEpochMillis;
        private final long durationMillis;
        private final int width;
        private final int height;
        private final boolean favorite;
        private final boolean protectedFromTrash;
        private final boolean trashed;
        private final boolean canRename;
        private final boolean canTrash;
        private final boolean canRestore;
        private final boolean canFavorite;
        private final boolean canProtect;

        public MediaItem(
                String repositoryId,
                String displayName,
                String mimeType,
                Kind kind,
                long size,
                long modifiedEpochMillis,
                long durationMillis,
                int width,
                int height
        ) {
            this(repositoryId, displayName, mimeType, kind, size, modifiedEpochMillis,
                    durationMillis, width, height, false, false, false,
                    false, false, false, false, false);
        }

        public MediaItem(
                String repositoryId,
                String displayName,
                String mimeType,
                Kind kind,
                long size,
                long modifiedEpochMillis,
                long durationMillis,
                int width,
                int height,
                boolean favorite,
                boolean protectedFromTrash,
                boolean trashed
        ) {
            this(repositoryId, displayName, mimeType, kind, size, modifiedEpochMillis,
                    durationMillis, width, height, favorite, protectedFromTrash, trashed,
                    false, false, false, false, false);
        }

        /**
         * 建立包含逐項操作能力的安全 DTO。能力預設必須為 false，只有 repository 已明確
         * 證實可執行的操作才能設為 true。
         */
        public MediaItem(
                String repositoryId,
                String displayName,
                String mimeType,
                Kind kind,
                long size,
                long modifiedEpochMillis,
                long durationMillis,
                int width,
                int height,
                boolean favorite,
                boolean protectedFromTrash,
                boolean trashed,
                boolean canRename,
                boolean canTrash,
                boolean canRestore,
                boolean canFavorite,
                boolean canProtect
        ) {
            this.repositoryId = requireText(repositoryId, "repositoryId");
            this.displayName = requireText(displayName, "displayName");
            this.mimeType = mimeType == null || mimeType.isEmpty()
                    ? "application/octet-stream" : mimeType;
            this.kind = kind == null ? Kind.OTHER : kind;
            this.size = Math.max(0L, size);
            this.modifiedEpochMillis = Math.max(0L, modifiedEpochMillis);
            this.durationMillis = Math.max(0L, durationMillis);
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.favorite = favorite;
            this.protectedFromTrash = protectedFromTrash;
            this.trashed = trashed;
            this.canRename = canRename;
            this.canTrash = canTrash;
            this.canRestore = canRestore;
            this.canFavorite = canFavorite;
            this.canProtect = canProtect;
        }

        public static MediaItem basic(
                String repositoryId,
                String displayName,
                String mimeType,
                Kind kind,
                long size,
                long modifiedEpochMillis
        ) {
            return new MediaItem(repositoryId, displayName, mimeType, kind, size,
                    modifiedEpochMillis, 0L, 0, 0);
        }

        public String getRepositoryId() {
            return repositoryId;
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

        public long getSize() {
            return size;
        }

        public long getModifiedEpochMillis() {
            return modifiedEpochMillis;
        }

        public long getDurationMillis() {
            return durationMillis;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public boolean isFavorite() {
            return favorite;
        }

        public boolean isProtectedFromTrash() {
            return protectedFromTrash;
        }

        public boolean isTrashed() {
            return trashed;
        }

        public boolean canRename() {
            return canRename;
        }

        public boolean canTrash() {
            return canTrash;
        }

        public boolean canRestore() {
            return canRestore;
        }

        public boolean canFavorite() {
            return canFavorite;
        }

        public boolean canProtect() {
            return canProtect;
        }

        private static String requireText(String value, String field) {
            Objects.requireNonNull(value, field);
            if (value.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            return value;
        }
    }

    /** 一組經 repository 驗證內容相同的項目。 */
    final class DuplicateGroup {
        private final String fingerprint;
        private final List<MediaItem> items;
        private final long reclaimableBytes;

        public DuplicateGroup(String fingerprint, List<MediaItem> items, long reclaimableBytes) {
            this.fingerprint = requireValue(fingerprint, "fingerprint");
            Objects.requireNonNull(items, "items");
            if (items.size() < 2) {
                throw new IllegalArgumentException("duplicate group requires at least two items");
            }
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.reclaimableBytes = Math.max(0L, reclaimableBytes);
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public List<MediaItem> getItems() {
            return items;
        }

        public long getReclaimableBytes() {
            return reclaimableBytes;
        }

        private static String requireValue(String value, String field) {
            Objects.requireNonNull(value, field);
            if (value.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            return value;
        }
    }

    /**
     * 已開啟的唯讀資料。length 是從目前 offset 起算的串流長度；totalLength 是完整媒體
     * 長度。縮圖通常兩者相同。
     */
    final class ReadResource implements Closeable {
        private final InputStream inputStream;
        private final String mimeType;
        private final long length;
        private final long totalLength;

        public ReadResource(
                InputStream inputStream,
                String mimeType,
                long length,
                long totalLength
        ) {
            this.inputStream = Objects.requireNonNull(inputStream, "inputStream");
            this.mimeType = mimeType == null || mimeType.isEmpty()
                    ? "application/octet-stream" : mimeType;
            if (length < 0L || totalLength < 0L || length > totalLength) {
                throw new IllegalArgumentException("invalid resource length");
            }
            this.length = length;
            this.totalLength = totalLength;
        }

        public static ReadResource complete(
                InputStream inputStream,
                String mimeType,
                long length
        ) {
            return new ReadResource(inputStream, mimeType, length, length);
        }

        public InputStream getInputStream() {
            return inputStream;
        }

        public String getMimeType() {
            return mimeType;
        }

        public long getLength() {
            return length;
        }

        public long getTotalLength() {
            return totalLength;
        }

        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }

    /** 可讓 repository 把預期錯誤映射成不洩漏內部資訊的 HTTP 狀態。 */
    class Failure extends IOException {
        private static final long serialVersionUID = 1L;

        public enum Reason {
            NOT_FOUND,
            INVALID,
            CONFLICT,
            READ_ONLY,
            NO_SPACE,
            BUSY,
            UNSUPPORTED
        }

        private final Reason reason;

        public Failure(Reason reason, String safeMessage) {
            super(safeMessage == null ? "媒體操作失敗" : safeMessage);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Failure(Reason reason, String safeMessage, Throwable cause) {
            super(safeMessage == null ? "媒體操作失敗" : safeMessage, cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Reason getReason() {
            return reason;
        }
    }

    /** 方便唯讀實作明確拒絕異動。 */
    abstract class ReadOnly implements MediaAccess {
        @Override
        public void moveToTrash(String repositoryId) throws IOException {
            throw new Failure(Failure.Reason.READ_ONLY, "目前儲存區為唯讀");
        }

        @Override
        public MediaItem renameMedia(String repositoryId, String newDisplayName)
                throws IOException {
            throw new Failure(Failure.Reason.READ_ONLY, "目前儲存區為唯讀");
        }

        @Override
        public MediaItem uploadMedia(
                String displayName,
                String mimeType,
                InputStream body,
                long contentLength
        ) throws IOException {
            throw new Failure(Failure.Reason.READ_ONLY, "目前儲存區為唯讀");
        }

        protected final List<MediaItem> noMedia() {
            return Collections.emptyList();
        }
    }
}
