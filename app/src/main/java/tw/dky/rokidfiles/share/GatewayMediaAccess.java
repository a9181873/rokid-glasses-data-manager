package tw.dky.rokidfiles.share;

import android.graphics.Bitmap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import tw.dky.rokidfiles.storage.CancellationToken;
import tw.dky.rokidfiles.storage.DuplicateScanner;
import tw.dky.rokidfiles.storage.ManagedStorageGateway;
import tw.dky.rokidfiles.storage.MediaPage;
import tw.dky.rokidfiles.storage.MediaQuery;
import tw.dky.rokidfiles.storage.StorageGateway;
import tw.dky.rokidfiles.storage.StorageOperationResult;

/** 將安全儲存層接到本機分享伺服器；HTTP 層永遠看不到實體路徑。 */
public final class GatewayMediaAccess implements MediaAccess {
    private static final int MAX_ITEMS = 10_000;
    private static final int MAX_DIRECTORIES = 10_000;
    private static final int THUMBNAIL_EDGE_LIMIT = 640;

    private final StorageGateway gateway;
    private final boolean managedMetadata;
    private final Object mutationLock = new Object();
    private final AtomicBoolean duplicateScanRunning = new AtomicBoolean(false);

    public GatewayMediaAccess(StorageGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.managedMetadata = gateway instanceof ManagedStorageGateway;
    }

    @Override
    public List<MediaAccess.MediaItem> listMedia() throws IOException {
        return convert(walk(false));
    }

    @Override
    public ReadResource openMedia(String repositoryId, long offset) throws IOException {
        tw.dky.rokidfiles.storage.MediaItem item = file(repositoryId);
        long total = item.getSizeBytes();
        if (total < 0L || offset < 0L || offset > total) {
            throw new Failure(Failure.Reason.INVALID, "檔案範圍無效");
        }
        InputStream stream;
        try {
            stream = gateway.openInputStream(repositoryId);
        } catch (FileNotFoundException | NoSuchFileException missing) {
            throw notFound(missing);
        }
        boolean ready = false;
        try {
            skipFully(stream, offset);
            ready = true;
            return new ReadResource(stream, item.getMimeType(), total - offset, total);
        } finally {
            if (!ready) {
                stream.close();
            }
        }
    }

    @Override
    public ReadResource openThumbnail(String repositoryId, int maxEdgePixels)
            throws IOException {
        file(repositoryId);
        int edge = Math.max(64, Math.min(maxEdgePixels, THUMBNAIL_EDGE_LIMIT));
        Bitmap bitmap;
        try {
            bitmap = gateway.loadThumbnail(repositoryId, edge, edge);
        } catch (FileNotFoundException | NoSuchFileException missing) {
            throw notFound(missing);
        }
        if (bitmap == null) {
            return null;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                throw new IOException("無法編碼縮圖");
            }
        } finally {
            bitmap.recycle();
        }
        byte[] bytes = output.toByteArray();
        return ReadResource.complete(new ByteArrayInputStream(bytes), "image/jpeg", bytes.length);
    }

    @Override
    public void moveToTrash(String repositoryId) throws IOException {
        synchronized (mutationLock) {
            requireCapability(file(repositoryId),
                    tw.dky.rokidfiles.storage.MediaItem.CAPABILITY_TRASH,
                    "此檔案不支援移到垃圾桶");
            requireSuccess(gateway.moveToTrash(repositoryId));
        }
    }

    @Override
    public MediaAccess.MediaItem renameMedia(String repositoryId, String newDisplayName)
            throws IOException {
        synchronized (mutationLock) {
            requireCapability(file(repositoryId),
                    tw.dky.rokidfiles.storage.MediaItem.CAPABILITY_RENAME,
                    "此檔案不支援重新命名");
            return convert(requireItem(gateway.rename(repositoryId, newDisplayName)));
        }
    }

    @Override
    public MediaAccess.MediaItem uploadMedia(
            String displayName,
            String mimeType,
            InputStream body,
            long contentLength
    ) throws IOException {
        synchronized (mutationLock) {
            return convert(requireItem(gateway.publishUpload(
                    null, displayName, mimeType, body, contentLength)));
        }
    }

    @Override
    public List<MediaAccess.MediaItem> listTrash() throws IOException {
        return convert(walk(true));
    }

    @Override
    public MediaAccess.MediaItem restoreMedia(String repositoryId) throws IOException {
        synchronized (mutationLock) {
            requireCapability(file(repositoryId),
                    tw.dky.rokidfiles.storage.MediaItem.CAPABILITY_RESTORE,
                    "此檔案不支援還原");
            return convert(requireItem(gateway.restore(repositoryId)));
        }
    }

    @Override
    public MediaAccess.MediaItem setFavorite(String repositoryId, boolean favorite)
            throws IOException {
        ManagedStorageGateway managed = managed();
        synchronized (mutationLock) {
            tw.dky.rokidfiles.storage.MediaItem item = file(repositoryId);
            if (!managed.setFavorite(item, favorite)) {
                throw new Failure(Failure.Reason.BUSY, "最愛標記未能保存");
            }
            return convert(gateway.getItem(repositoryId));
        }
    }

    @Override
    public MediaAccess.MediaItem setProtected(
            String repositoryId,
            boolean protectedFromTrash
    ) throws IOException {
        ManagedStorageGateway managed = managed();
        synchronized (mutationLock) {
            tw.dky.rokidfiles.storage.MediaItem item = file(repositoryId);
            if (!managed.setProtected(item, protectedFromTrash)) {
                throw new Failure(Failure.Reason.BUSY, "保護標記未能保存");
            }
            return convert(gateway.getItem(repositoryId));
        }
    }

    @Override
    public List<DuplicateGroup> scanDuplicates() throws IOException {
        if (!duplicateScanRunning.compareAndSet(false, true)) {
            throw new Failure(Failure.Reason.BUSY, "重複檔掃描已在進行中");
        }
        try {
            CancellationToken interrupted = () -> Thread.currentThread().isInterrupted();
            DuplicateScanner.Result result = new DuplicateScanner(gateway).scan(
                    DuplicateScanner.Options.defaults(), interrupted, null);
            if (result.isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new Failure(Failure.Reason.BUSY, "重複檔掃描已取消");
            }
            List<DuplicateGroup> groups = new ArrayList<>(result.getGroups().size());
            for (DuplicateScanner.Group group : result.getGroups()) {
                groups.add(new DuplicateGroup(
                        group.getId(), convert(group.getItems()), group.getRecoverableBytes()));
            }
            return Collections.unmodifiableList(groups);
        } finally {
            duplicateScanRunning.set(false);
        }
    }

    private ManagedStorageGateway managed() throws Failure {
        if (gateway instanceof ManagedStorageGateway) {
            return (ManagedStorageGateway) gateway;
        }
        throw new Failure(Failure.Reason.UNSUPPORTED, "目前儲存區不支援本機標記");
    }

    private tw.dky.rokidfiles.storage.MediaItem file(String id) throws IOException {
        try {
            tw.dky.rokidfiles.storage.MediaItem item = gateway.getItem(id);
            if (item.getKind() == tw.dky.rokidfiles.storage.MediaItem.Kind.DIRECTORY) {
                throw new Failure(Failure.Reason.INVALID, "指定項目不是媒體檔案");
            }
            return item;
        } catch (Failure known) {
            throw known;
        } catch (FileNotFoundException | NoSuchFileException missing) {
            throw notFound(missing);
        } catch (SecurityException denied) {
            throw new Failure(Failure.Reason.READ_ONLY, "沒有權限讀取此檔案", denied);
        } catch (IllegalArgumentException missing) {
            throw new Failure(Failure.Reason.NOT_FOUND, "找不到檔案", missing);
        }
    }

    private List<tw.dky.rokidfiles.storage.MediaItem> walk(boolean trash) throws IOException {
        List<tw.dky.rokidfiles.storage.MediaItem> result = new ArrayList<>();
        if (trash) {
            int offset = 0;
            while (result.size() < MAX_ITEMS) {
                MediaPage page = gateway.list(MediaQuery.trash(offset, MediaQuery.MAX_PAGE_SIZE));
                result.addAll(page.getItems());
                if (!page.hasMore() || page.getNextOffset() <= offset) break;
                offset = page.getNextOffset();
            }
            return result;
        }

        ArrayDeque<String> directories = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        directories.add("<root>");
        visited.add("<root>");
        while (!directories.isEmpty() && result.size() < MAX_ITEMS
                && visited.size() <= MAX_DIRECTORIES) {
            String marker = directories.removeFirst();
            String parentId = "<root>".equals(marker) ? null : marker;
            int offset = 0;
            while (result.size() < MAX_ITEMS) {
                MediaPage page = gateway.list(new MediaQuery(
                        parentId, offset, MediaQuery.MAX_PAGE_SIZE, false));
                for (tw.dky.rokidfiles.storage.MediaItem item : page.getItems()) {
                    if (item.getKind()
                            == tw.dky.rokidfiles.storage.MediaItem.Kind.DIRECTORY) {
                        if (visited.add(item.getId())) directories.addLast(item.getId());
                    } else {
                        result.add(item);
                    }
                }
                if (!page.hasMore() || page.getNextOffset() <= offset) break;
                offset = page.getNextOffset();
            }
        }
        return result;
    }

    private List<MediaAccess.MediaItem> convert(
            List<tw.dky.rokidfiles.storage.MediaItem> source) {
        List<MediaAccess.MediaItem> result = new ArrayList<>(source.size());
        for (tw.dky.rokidfiles.storage.MediaItem item : source) {
            result.add(convert(item));
        }
        return Collections.unmodifiableList(result);
    }

    private MediaAccess.MediaItem convert(
            tw.dky.rokidfiles.storage.MediaItem item) {
        Kind kind;
        switch (item.getKind()) {
            case IMAGE:
                kind = Kind.PHOTO;
                break;
            case VIDEO:
                kind = Kind.VIDEO;
                break;
            default:
                kind = Kind.OTHER;
                break;
        }
        return new MediaAccess.MediaItem(
                item.getId(),
                item.getDisplayName(),
                item.getMimeType(),
                kind,
                Math.max(0L, item.getSizeBytes()),
                item.getLastModifiedMillis(),
                0L,
                0,
                0,
                item.isFavorite(),
                item.isProtected(),
                item.isTrashed(),
                item.hasCapability(tw.dky.rokidfiles.storage.MediaItem.CAPABILITY_RENAME),
                item.hasCapability(tw.dky.rokidfiles.storage.MediaItem.CAPABILITY_TRASH),
                item.hasCapability(tw.dky.rokidfiles.storage.MediaItem.CAPABILITY_RESTORE),
                managedMetadata,
                managedMetadata);
    }

    private static void requireCapability(
            tw.dky.rokidfiles.storage.MediaItem item,
            int capability,
            String safeMessage
    ) throws Failure {
        if (!item.hasCapability(capability)) {
            throw new Failure(Failure.Reason.READ_ONLY, safeMessage);
        }
    }

    private static tw.dky.rokidfiles.storage.MediaItem requireItem(
            StorageOperationResult result)
            throws Failure {
        requireSuccess(result);
        if (result.getItem() == null) {
            throw new Failure(Failure.Reason.BUSY, "操作完成但無法重新讀取檔案");
        }
        return result.getItem();
    }

    private static void requireSuccess(StorageOperationResult result) throws Failure {
        if (result == null) {
            throw new Failure(Failure.Reason.BUSY, "儲存操作沒有回應");
        }
        if (result.isSuccess()) return;
        Failure.Reason reason;
        switch (result.getStatus()) {
            case NOT_FOUND:
                reason = Failure.Reason.NOT_FOUND;
                break;
            case INVALID_INPUT:
                reason = Failure.Reason.INVALID;
                break;
            case CONFLICT:
                reason = Failure.Reason.CONFLICT;
                break;
            case PERMISSION_DENIED:
            case PROTECTED:
                reason = Failure.Reason.READ_ONLY;
                break;
            case UNSUPPORTED:
                reason = Failure.Reason.UNSUPPORTED;
                break;
            case IO_ERROR:
            default:
                reason = Failure.Reason.BUSY;
                break;
        }
        String diagnostic = result.getMessage();
        IOException cause = diagnostic.isEmpty() ? null : new IOException(diagnostic);
        throw new Failure(reason, safeFailureMessage(reason), cause);
    }

    private static String safeFailureMessage(Failure.Reason reason) {
        switch (reason) {
            case NOT_FOUND:
                return "檔案不存在或已移動";
            case INVALID:
                return "檔案操作內容無效";
            case CONFLICT:
                return "檔案名稱或狀態發生衝突";
            case READ_ONLY:
                return "此檔案目前為唯讀";
            case NO_SPACE:
                return "儲存空間不足";
            case UNSUPPORTED:
                return "目前儲存區不支援此操作";
            case BUSY:
            default:
                return "媒體操作暫時無法完成";
        }
    }

    private static void skipFully(InputStream stream, long bytes) throws IOException {
        long remaining = bytes;
        byte[] scratch = null;
        while (remaining > 0L) {
            long skipped = stream.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (scratch == null) scratch = new byte[16 * 1024];
            int read = stream.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read < 0) throw new EOFException("檔案在指定偏移前已結束");
            remaining -= read;
        }
    }

    private static Failure notFound(IOException cause) {
        return new Failure(Failure.Reason.NOT_FOUND, "檔案已移動或不存在", cause);
    }
}
