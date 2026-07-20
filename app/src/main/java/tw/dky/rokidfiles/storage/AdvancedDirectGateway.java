package tw.dky.rokidfiles.storage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in MANAGE_EXTERNAL_STORAGE backend. Every operation is confined to four explicit roots and
 * rejects symbolic links and canonical-path escapes.
 */
@SuppressLint({"ApplySharedPref", "UsableSpace"})
public final class AdvancedDirectGateway implements StorageGateway {
    private static final String TRASH_DIRECTORY = ".RokidFilesTrash";
    private static final String UPLOAD_PREFIX = ".rokid-upload-";
    private static final Pattern TRASH_FILE_NAME = Pattern.compile(
            "\\A([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12})_(.+)\\z");
    private static final String PREFERENCES = "rokid_files_direct_trash_v1";
    private static final long RESERVED_FREE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_DIRECTORY_ENTRIES = 20_000;
    private static final Set<Path> ACTIVE_UPLOADS = ConcurrentHashMap.newKeySet();

    private final Context context;
    private final File externalRoot;
    private final String externalCanonicalPath;
    private final List<AllowedRoot> allowedRoots;
    private final SharedPreferences trashMetadata;

    public AdvancedDirectGateway(Context context) throws IOException {
        this(context, Environment.getExternalStorageDirectory());
    }

    /** Visible for tests; production callers should use {@link #AdvancedDirectGateway(Context)}. */
    AdvancedDirectGateway(Context context, File externalRoot) throws IOException {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.externalRoot = Objects.requireNonNull(externalRoot, "externalRoot").getCanonicalFile();
        this.externalCanonicalPath = this.externalRoot.getCanonicalPath();
        this.allowedRoots = buildAllowedRoots(this.externalRoot);
        this.trashMetadata = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        cleanupStaleUploadsBestEffort();
    }

    @Override
    public boolean isAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Environment.isExternalStorageManager();
    }

    @Override
    public MediaItem.Backend getBackend() {
        return MediaItem.Backend.ADVANCED_DIRECT;
    }

    @Override
    public StorageCapacity getCapacitySummary() {
        StatFs stat = new StatFs(externalRoot.getAbsolutePath());
        return StorageCapacity.basic(stat.getTotalBytes(), stat.getAvailableBytes());
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new SecurityException(
                    "尚未允許檔案管理權限");
        }
    }

    @Override
    public MediaPage list(MediaQuery query) throws IOException, SecurityException {
        Objects.requireNonNull(query, "query");
        ensureAvailable();
        if (query.isTrash()) {
            return listTrash(query);
        }
        if (query.getParentId() == null) {
            return listVirtualRoot(query);
        }
        DirectRef parentReference = DirectRef.decode(query.getParentId());
        if (parentReference.trashed) {
            throw new IllegalArgumentException("Use MediaQuery.trash() to browse trash");
        }
        File parent = resolveExisting(parentReference.relativePath, false);
        if (!parent.isDirectory()) {
            throw new IllegalArgumentException("parentId is not a directory");
        }
        return listDirectory(parent, query, false);
    }

    @Override
    public MediaItem getItem(String id) throws IOException, SecurityException {
        ensureAvailable();
        DirectRef reference = DirectRef.decode(id);
        File file = resolveExisting(reference.relativePath, reference.trashed);
        return toItem(file, reference.trashed);
    }

    @Override
    public InputStream openInputStream(String id) throws IOException, SecurityException {
        ensureAvailable();
        DirectRef reference = DirectRef.decode(id);
        File file = resolveExisting(reference.relativePath, reference.trashed);
        if (!file.isFile()) {
            throw new FileNotFoundException("Cannot open a directory as a stream");
        }
        return new ParcelFileDescriptor.AutoCloseInputStream(openVerifiedReadDescriptor(file));
    }

    @Override
    public AssetFileDescriptor openAssetFileDescriptor(String id)
            throws IOException, SecurityException {
        ensureAvailable();
        DirectRef reference = DirectRef.decode(id);
        File file = resolveExisting(reference.relativePath, reference.trashed);
        if (!file.isFile()) {
            throw new FileNotFoundException("Cannot open a directory descriptor");
        }
        ParcelFileDescriptor descriptor = openVerifiedReadDescriptor(file);
        return new AssetFileDescriptor(descriptor, 0, file.length());
    }

    @Override
    public Bitmap loadThumbnail(String id, int width, int height)
            throws IOException, SecurityException {
        MediaItem item = getItem(id);
        if (item.getKind() == MediaItem.Kind.DIRECTORY) {
            return null;
        }
        File file = resolveExisting(
                DirectRef.decode(id).relativePath, DirectRef.decode(id).trashed);
        return StorageSupport.loadFileThumbnail(
                file, item.getMimeType(), width, height);
    }

    @Override
    public StorageOperationResult rename(String id, String newDisplayName) {
        if (!StorageSupport.isSafeDisplayName(newDisplayName)
                || newDisplayName.startsWith(UPLOAD_PREFIX)
                || TRASH_DIRECTORY.equals(newDisplayName)) {
            return StorageOperationResult.failure(
                    StorageOperationResult.Status.INVALID_INPUT, "Invalid display name");
        }
        try {
            ensureAvailable();
            DirectRef reference = DirectRef.decode(id);
            if (reference.trashed) {
                return StorageOperationResult.unsupported(
                        "Files in trash must be restored before renaming");
            }
            File source = resolveExisting(reference.relativePath, reference.trashed);
            if (!source.isFile()) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.INVALID_INPUT,
                        "Only media files can be renamed");
            }
            File target = resolveNewChild(source.getParentFile(), newDisplayName.trim(),
                    reference.trashed);
            if (target.exists()) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.CONFLICT, "A file with that name already exists");
            }
            atomicMove(source.toPath(), target.toPath());
            return StorageOperationResult.success(toItem(target, reference.trashed));
        } catch (FileAlreadyExistsException failure) {
            return failure(StorageOperationResult.Status.CONFLICT, failure);
        } catch (FileNotFoundException failure) {
            return failure(StorageOperationResult.Status.NOT_FOUND, failure);
        } catch (SecurityException failure) {
            return failure(StorageOperationResult.Status.PERMISSION_DENIED, failure);
        } catch (IllegalArgumentException failure) {
            return failure(StorageOperationResult.Status.INVALID_INPUT, failure);
        } catch (IOException | RuntimeException failure) {
            return failure(StorageOperationResult.Status.IO_ERROR, failure);
        }
    }

    @Override
    public StorageOperationResult moveToTrash(String id) {
        String stagedMetadataKey = null;
        boolean moveCompleted = false;
        try {
            ensureAvailable();
            DirectRef reference = DirectRef.decode(id);
            if (reference.trashed) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.INVALID_INPUT, "Item is already in trash");
            }
            File source = resolveExisting(reference.relativePath, false);
            if (!source.isFile()) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.INVALID_INPUT,
                        "Only media files can be trashed");
            }
            AllowedRoot allowedRoot = findAllowedRoot(source);
            File trashDirectory = ensureTrashDirectory(allowedRoot);
            String targetName = UUID.randomUUID().toString() + "_" + source.getName();
            File target = resolveNewChild(trashDirectory, targetName, true);
            String originalPath = relativePath(source);
            stagedMetadataKey = metadataKey(relativePath(target));
            if (!trashMetadata.edit()
                    .putString(stagedMetadataKey, encodePart(originalPath))
                    .commit()) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.IO_ERROR,
                        "Restore metadata could not be saved; the file was not moved");
            }
            atomicMove(source.toPath(), target.toPath());
            moveCompleted = true;
            return StorageOperationResult.success(toItem(target, true));
        } catch (FileAlreadyExistsException failure) {
            return failure(StorageOperationResult.Status.CONFLICT, failure);
        } catch (FileNotFoundException failure) {
            return failure(StorageOperationResult.Status.NOT_FOUND, failure);
        } catch (SecurityException failure) {
            return failure(StorageOperationResult.Status.PERMISSION_DENIED, failure);
        } catch (IllegalArgumentException failure) {
            return failure(StorageOperationResult.Status.INVALID_INPUT, failure);
        } catch (IOException | RuntimeException failure) {
            return failure(StorageOperationResult.Status.IO_ERROR, failure);
        } finally {
            if (stagedMetadataKey != null && !moveCompleted) {
                trashMetadata.edit().remove(stagedMetadataKey).commit();
            }
        }
    }

    @Override
    public StorageOperationResult restore(String id) {
        try {
            ensureAvailable();
            DirectRef reference = DirectRef.decode(id);
            if (!reference.trashed) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.INVALID_INPUT, "Item is not in trash");
            }
            File source = resolveExisting(reference.relativePath, true);
            String encodedOriginal = trashMetadata.getString(
                    metadataKey(reference.relativePath), null);
            if (encodedOriginal == null) {
                return StorageOperationResult.unsupported("Original path metadata is missing");
            }
            String originalRelativePath;
            try {
                originalRelativePath = decodePart(encodedOriginal);
            } catch (IllegalArgumentException corrupt) {
                return StorageOperationResult.unsupported("Original path metadata is invalid");
            }
            File target = resolveNewPath(originalRelativePath, false);
            File parent = target.getParentFile();
            if (parent == null || !parent.isDirectory()) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.NOT_FOUND,
                        "Original directory no longer exists");
            }
            resolveExisting(relativePath(parent), false);
            if (target.exists()) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.CONFLICT,
                        "A file already exists at the original path");
            }
            atomicMove(source.toPath(), target.toPath());
            trashMetadata.edit().remove(metadataKey(reference.relativePath)).commit();
            return StorageOperationResult.success(toItem(target, false));
        } catch (FileAlreadyExistsException failure) {
            return failure(StorageOperationResult.Status.CONFLICT, failure);
        } catch (FileNotFoundException failure) {
            return failure(StorageOperationResult.Status.NOT_FOUND, failure);
        } catch (SecurityException failure) {
            return failure(StorageOperationResult.Status.PERMISSION_DENIED, failure);
        } catch (IllegalArgumentException failure) {
            return failure(StorageOperationResult.Status.INVALID_INPUT, failure);
        } catch (IOException | RuntimeException failure) {
            return failure(StorageOperationResult.Status.IO_ERROR, failure);
        }
    }

    @Override
    public StorageOperationResult publishUpload(
            String parentId,
            String displayName,
            String mimeType,
            InputStream source,
            long contentLength) {
        Objects.requireNonNull(source, "source");
        if (!StorageSupport.isSafeDisplayName(displayName)
                || displayName.startsWith(UPLOAD_PREFIX)
                || TRASH_DIRECTORY.equals(displayName)
                || contentLength < -1L) {
            return StorageOperationResult.failure(
                    StorageOperationResult.Status.INVALID_INPUT, "Invalid upload metadata");
        }
        String safeMime = StorageSupport.safeMimeType(mimeType, displayName);
        if (!(safeMime.startsWith("image/") || safeMime.startsWith("video/"))) {
            return StorageOperationResult.failure(
                    StorageOperationResult.Status.INVALID_INPUT,
                    "Only image and video uploads are accepted");
        }

        File temporary = null;
        Path activeTemporaryPath = null;
        try {
            ensureAvailable();
            File parent;
            if (parentId == null) {
                parent = null;
                for (AllowedRoot root : allowedRoots) {
                    if (root.file.isDirectory() && !Files.isSymbolicLink(root.file.toPath())) {
                        parent = root.file;
                        break;
                    }
                }
                if (parent == null) {
                    throw new FileNotFoundException("No allowlisted media directory exists");
                }
            } else {
                DirectRef parentReference = DirectRef.decode(parentId);
                if (parentReference.trashed) {
                    throw new IllegalArgumentException("Cannot upload into trash");
                }
                parent = resolveExisting(parentReference.relativePath, false);
            }
            if (!parent.isDirectory()) {
                throw new IllegalArgumentException("Upload parent is not a directory");
            }
            File target = resolveNewChild(parent, displayName.trim(), false);
            if (target.exists()) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.CONFLICT,
                        "A file with that name already exists");
            }
            long writableBudget = Math.max(0L, parent.getUsableSpace() - RESERVED_FREE_BYTES);
            if (contentLength >= 0L && contentLength > writableBudget) {
                return StorageOperationResult.failure(
                        StorageOperationResult.Status.IO_ERROR,
                        "Not enough free space while preserving the safety reserve");
            }

            temporary = resolveNewChild(
                    parent, UPLOAD_PREFIX + UUID.randomUUID() + ".part", false);
            activeTemporaryPath = normalizedAbsolutePath(temporary.toPath());
            if (!ACTIVE_UPLOADS.add(activeTemporaryPath)) {
                throw new IOException("Upload temporary path is already active");
            }
            Files.createFile(temporary.toPath());
            long copied = 0L;
            byte[] buffer = new byte[StorageSupport.COPY_BUFFER_BYTES];
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                int read;
                while ((read = source.read(buffer)) != -1) {
                    copied += read;
                    if (copied > writableBudget) {
                        throw new IOException(
                                "Upload exceeded free space safety limit");
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
                output.getFD().sync();
            }
            if (contentLength >= 0L && copied != contentLength) {
                throw new IOException("Upload length did not match Content-Length");
            }
            if (!StorageSupport.hasRecognizedMediaSignature(temporary, safeMime)) {
                throw new IOException("Uploaded content is not a recognized image or video");
            }
            atomicPublish(temporary.toPath(), target.toPath());
            temporary = null;
            return StorageOperationResult.success(toItem(target, false));
        } catch (FileAlreadyExistsException failure) {
            return failure(StorageOperationResult.Status.CONFLICT, failure);
        } catch (SecurityException failure) {
            return failure(StorageOperationResult.Status.PERMISSION_DENIED, failure);
        } catch (IllegalArgumentException failure) {
            return failure(StorageOperationResult.Status.INVALID_INPUT, failure);
        } catch (IOException | RuntimeException failure) {
            return failure(StorageOperationResult.Status.IO_ERROR, failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException | RuntimeException ignored) {
                    // 保留未發布暫存檔；日後具備權限時只會清理逾 24 小時的安全命名檔。
                }
            }
            if (activeTemporaryPath != null) {
                ACTIVE_UPLOADS.remove(activeTemporaryPath);
            }
        }
    }

    /**
     * Best-effort startup maintenance. Cleanup never escapes the four allowlisted roots and no
     * cleanup failure is allowed to make gateway construction fail.
     */
    private void cleanupStaleUploadsBestEffort() {
        try {
            if (!isAvailable()) {
                return;
            }
            List<Path> roots = new ArrayList<>(allowedRoots.size());
            for (AllowedRoot root : allowedRoots) {
                roots.add(root.file.toPath());
            }
            StaleUploadCleaner.clean(roots, ACTIVE_UPLOADS, System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // Permission and storage state can change while the app is starting.
        }
    }

    private static Path normalizedAbsolutePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private MediaPage listVirtualRoot(MediaQuery query) throws IOException {
        List<MediaItem> entries = new ArrayList<>(allowedRoots.size());
        for (AllowedRoot root : allowedRoots) {
            if (root.file.isDirectory() && !Files.isSymbolicLink(root.file.toPath())) {
                entries.add(toItem(root.file, false));
            }
        }
        entries.sort(Comparator.comparing(MediaItem::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        return slice(entries, query);
    }

    private MediaPage listDirectory(File parent, MediaQuery query, boolean trashed)
            throws IOException {
        List<MediaItem> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent.toPath())) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (TRASH_DIRECTORY.equals(name) || name.startsWith(UPLOAD_PREFIX)
                        || Files.isSymbolicLink(path)) {
                    continue;
                }
                File child = path.toFile();
                try {
                    resolveExisting(relativePath(child), trashed);
                } catch (IOException | SecurityException rejected) {
                    continue;
                }
                if (!child.isDirectory() && mediaKind(child) == null) {
                    continue;
                }
                if (items.size() >= MAX_DIRECTORY_ENTRIES) {
                    throw new IOException("Directory contains too many manageable entries");
                }
                items.add(toItem(child, trashed));
            }
        }
        items.sort(Comparator
                .comparing(MediaItem::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MediaItem::getId));
        return slice(items, query);
    }

    private MediaPage listTrash(MediaQuery query) throws IOException {
        List<MediaItem> items = new ArrayList<>();
        for (AllowedRoot root : allowedRoots) {
            File trash = new File(root.file, TRASH_DIRECTORY);
            if (!trash.isDirectory() || Files.isSymbolicLink(trash.toPath())) {
                continue;
            }
            try {
                rejectSymbolicLinks(root, trash);
            } catch (SecurityException rejected) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(trash.toPath())) {
                for (Path path : stream) {
                    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || mediaKind(path.toFile()) == null) {
                        continue;
                    }
                    if (items.size() >= MAX_DIRECTORY_ENTRIES) {
                        throw new IOException("Trash contains too many manageable entries");
                    }
                    items.add(toItem(path.toFile(), true));
                }
            }
        }
        items.sort(Comparator.comparingLong(MediaItem::getLastModifiedMillis)
                .reversed().thenComparing(MediaItem::getId));
        return slice(items, query);
    }

    private static MediaPage slice(List<MediaItem> entries, MediaQuery query) {
        if (query.getOffset() >= entries.size()) {
            return new MediaPage(Collections.emptyList(), query.getOffset(), false);
        }
        int end = Math.min(entries.size(), query.getOffset() + query.getLimit());
        return new MediaPage(
                entries.subList(query.getOffset(), end),
                end,
                end < entries.size());
    }

    private MediaItem toItem(File file, boolean trashed) throws IOException {
        AllowedRoot root = findAllowedRoot(file);
        rejectSymbolicLinks(root, file);
        String relative = relativePath(file);
        boolean directory = file.isDirectory();
        MediaItem.Kind kind = directory ? MediaItem.Kind.DIRECTORY : mediaKind(file);
        if (kind == null) {
            throw new IOException("Unsupported non-media file");
        }
        String mime = directory
                ? DocumentsContract.Document.MIME_TYPE_DIR
                : detectMime(file, kind);
        int capabilities = MediaItem.CAPABILITY_READ;
        if (directory) {
            capabilities |= MediaItem.CAPABILITY_UPLOAD_CHILD;
        } else {
            capabilities |= MediaItem.CAPABILITY_THUMBNAIL;
            if (trashed) {
                if (trashMetadata.contains(metadataKey(relative))) {
                    capabilities |= MediaItem.CAPABILITY_RESTORE;
                }
            } else {
                capabilities |= MediaItem.CAPABILITY_RENAME | MediaItem.CAPABILITY_TRASH;
            }
        }
        File parent = file.getParentFile();
        String parentId = parent == null || file.equals(root.file)
                ? null
                : DirectRef.encode(relativePath(parent), trashed);
        String displayName = file.getName();
        if (trashed) {
            String original = trashMetadata.getString(metadataKey(relative), null);
            if (original != null) {
                try {
                    displayName = new File(decodePart(original)).getName();
                } catch (IllegalArgumentException ignored) {
                    // 損壞的還原資料不應阻擋使用者看見垃圾桶項目。
                }
            }
        }
        return new MediaItem(
                DirectRef.encode(relative, trashed),
                parentId,
                Uri.fromFile(file),
                displayName,
                mime,
                kind,
                MediaItem.Backend.ADVANCED_DIRECT,
                directory ? -1L : Files.size(file.toPath()),
                Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS).toMillis(),
                relative,
                trashed,
                capabilities);
    }

    private File resolveExisting(String relativePath, boolean allowTrash) throws IOException {
        File candidate = resolveNewPath(relativePath, allowTrash);
        if (!candidate.exists()) {
            throw new FileNotFoundException("File no longer exists");
        }
        AllowedRoot root = findAllowedRoot(candidate);
        rejectSymbolicLinks(root, candidate);
        boolean insideTrash = isInsideTrash(root, candidate);
        if (insideTrash != allowTrash) {
            throw new SecurityException("Trash state does not match the opaque item ID");
        }
        return candidate;
    }

    private File resolveNewPath(String relativePath, boolean allowTrash) throws IOException {
        if (relativePath == null || relativePath.isEmpty()) {
            throw new IllegalArgumentException("Empty direct item path");
        }
        Path relative = Paths.get(relativePath);
        if (relative.isAbsolute() || !relative.normalize().equals(relative)) {
            throw new SecurityException("Path traversal is not allowed");
        }
        Path lexicalPath = externalRoot.toPath().resolve(relative).toAbsolutePath().normalize();
        AllowedRoot root = findAllowedRootLexical(lexicalPath);
        Path existingPath = Files.exists(lexicalPath, LinkOption.NOFOLLOW_LINKS)
                ? lexicalPath : lexicalPath.getParent();
        if (existingPath == null || !Files.exists(existingPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileNotFoundException("Parent directory does not exist");
        }
        // 必須先驗證呼叫者提供的原始路徑；canonicalize 會把 symlink alias 消掉。
        rejectSymbolicLinks(root, existingPath.toFile());
        File candidate = lexicalPath.toFile().getCanonicalFile();
        if (findAllowedRoot(candidate) != root) {
            throw new SecurityException("Path changed its allowlisted root through an indirect path");
        }
        if (isInsideTrash(root, candidate) != allowTrash) {
            throw new SecurityException("Path is outside the requested storage area");
        }
        return candidate;
    }

    private File resolveNewChild(File parent, String displayName, boolean allowTrash)
            throws IOException {
        if (!StorageSupport.isSafeDisplayName(displayName)) {
            throw new IllegalArgumentException("Invalid display name");
        }
        resolveExisting(relativePath(parent), allowTrash);
        File child = new File(parent, displayName).getCanonicalFile();
        if (!child.getParentFile().equals(parent.getCanonicalFile())) {
            throw new SecurityException("Child path escaped its parent");
        }
        findAllowedRoot(child);
        return child;
    }

    private AllowedRoot findAllowedRoot(File candidate) throws IOException {
        String canonical = candidate.getCanonicalPath();
        for (AllowedRoot root : allowedRoots) {
            if (canonical.equals(root.canonicalPath)
                    || canonical.startsWith(root.canonicalPath + File.separator)) {
                return root;
            }
        }
        throw new SecurityException("Path is outside the direct-access allowlist");
    }

    private AllowedRoot findAllowedRootLexical(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        for (AllowedRoot root : allowedRoots) {
            Path rootPath = root.file.toPath().toAbsolutePath().normalize();
            if (normalized.startsWith(rootPath)) {
                return root;
            }
        }
        throw new SecurityException("Path is outside the direct-access allowlist");
    }

    private static void rejectSymbolicLinks(AllowedRoot root, File candidate) throws IOException {
        Path rootPath = root.file.toPath();
        Path candidatePath = candidate.toPath();
        if (!candidatePath.normalize().startsWith(rootPath.normalize())) {
            throw new SecurityException("Canonical path escaped its allowlisted root");
        }
        Path current = rootPath;
        if (Files.isSymbolicLink(current)) {
            throw new SecurityException("Symbolic-link roots are not allowed");
        }
        Path relative = rootPath.relativize(candidatePath);
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new SecurityException("Symbolic links are not allowed");
            }
        }
    }

    private static boolean isInsideTrash(AllowedRoot root, File candidate) throws IOException {
        File trash = new File(root.file, TRASH_DIRECTORY).getCanonicalFile();
        String path = candidate.getCanonicalPath();
        String trashPath = trash.getCanonicalPath();
        return path.equals(trashPath) || path.startsWith(trashPath + File.separator);
    }

    private ParcelFileDescriptor openVerifiedReadDescriptor(File file) throws IOException {
        rejectSymbolicLinks(findAllowedRoot(file), file);
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                file, ParcelFileDescriptor.MODE_READ_ONLY);
        boolean accepted = false;
        try {
            // Verify what was actually opened, closing the check/open race on shared storage.
            File opened = new File("/proc/self/fd/" + descriptor.getFd()).getCanonicalFile();
            if (!opened.getCanonicalPath().equals(file.getCanonicalPath())) {
                throw new SecurityException("Opened descriptor escaped the validated file path");
            }
            findAllowedRoot(opened);
            accepted = true;
            return descriptor;
        } finally {
            if (!accepted) {
                descriptor.close();
            }
        }
    }

    private File ensureTrashDirectory(AllowedRoot root) throws IOException {
        File trash = new File(root.file, TRASH_DIRECTORY);
        if (!trash.exists()) {
            Files.createDirectory(trash.toPath());
        }
        if (!trash.isDirectory()) {
            throw new IOException("Trash path is not a directory");
        }
        rejectSymbolicLinks(root, trash.getCanonicalFile());
        return trash.getCanonicalFile();
    }

    private String relativePath(File file) throws IOException {
        String canonical = file.getCanonicalPath();
        if (!canonical.equals(externalCanonicalPath)
                && !canonical.startsWith(externalCanonicalPath + File.separator)) {
            throw new SecurityException("Path escaped external storage");
        }
        return externalRoot.toPath().relativize(file.getCanonicalFile().toPath())
                .toString().replace(File.separatorChar, '/');
    }

    private static MediaItem.Kind mediaKind(File file) {
        String mime = detectMime(file, null);
        return StorageSupport.kindForMime(mime);
    }

    private static String detectMime(File file, MediaItem.Kind knownKind) {
        String mime = null;
        try {
            mime = Files.probeContentType(file.toPath());
        } catch (IOException ignored) {
            // Fall through to the extension-based Android MIME table.
        }
        if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/"))) {
            mime = StorageSupport.safeMimeType(null, file.getName());
        }
        if (knownKind == MediaItem.Kind.IMAGE && !mime.startsWith("image/")) {
            return "image/*";
        }
        if (knownKind == MediaItem.Kind.VIDEO && !mime.startsWith("video/")) {
            return "video/*";
        }
        return mime.toLowerCase(Locale.US);
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        // 不使用 ATOMIC_MOVE：部分 Unix provider 會在該模式忽略「不得覆寫」語意。
        Files.move(source, target);
    }

    private static void atomicPublish(Path source, Path target) throws IOException {
        // 暫存檔已完整 fsync；無 REPLACE_EXISTING 可確保競態下不覆寫既有媒體。
        Files.move(source, target);
    }

    private String metadataKey(String trashRelativePath) {
        return "direct." + encodePart(trashRelativePath);
    }

    private static StorageOperationResult failure(
            StorageOperationResult.Status status, Throwable failure) {
        String message = failure.getMessage();
        return StorageOperationResult.failure(
                status,
                message == null || message.trim().isEmpty()
                        ? failure.getClass().getSimpleName()
                        : message);
    }

    private static List<AllowedRoot> buildAllowedRoots(File externalRoot) throws IOException {
        List<AllowedRoot> roots = new ArrayList<>(4);
        String externalPath = externalRoot.getCanonicalPath();
        roots.add(new AllowedRoot(new File(externalRoot, "DCIM/Camera"), externalPath));
        roots.add(new AllowedRoot(new File(externalRoot, "DCIM/album"), externalPath));
        roots.add(new AllowedRoot(new File(externalRoot, "Pictures"), externalPath));
        roots.add(new AllowedRoot(new File(externalRoot, "Movies"), externalPath));
        return Collections.unmodifiableList(roots);
    }

    private static String encodePart(String value) {
        return Base64.encodeToString(
                value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String decodePart(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Empty direct item token");
        }
        return new String(
                Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                StandardCharsets.UTF_8);
    }

    private static final class AllowedRoot {
        final File file;
        final String canonicalPath;

        AllowedRoot(File file, String externalCanonicalPath) throws IOException {
            Path external = Paths.get(externalCanonicalPath).toAbsolutePath().normalize();
            Path requested = file.toPath().toAbsolutePath().normalize();
            if (!requested.startsWith(external)) {
                throw new SecurityException("Allowlisted root escaped external storage");
            }
            Path current = external;
            Path relative = external.relativize(requested);
            for (Path component : relative) {
                current = current.resolve(component);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSymbolicLink(current)) {
                    throw new SecurityException("Symbolic-link allowlist roots are not allowed");
                }
            }
            this.file = requested.toFile();
            this.canonicalPath = this.file.getCanonicalPath();
            if (!canonicalPath.equals(this.file.getAbsolutePath())) {
                throw new SecurityException("Allowlisted root contains an indirect path");
            }
            if (!canonicalPath.startsWith(externalCanonicalPath + File.separator)) {
                throw new SecurityException("Allowlisted root escaped external storage");
            }
        }
    }

    private static final class DirectRef {
        final String relativePath;
        final boolean trashed;

        DirectRef(String relativePath, boolean trashed) {
            this.relativePath = relativePath;
            this.trashed = trashed;
        }

        static String encode(String relativePath, boolean trashed) {
            return "direct." + encodePart(relativePath) + "." + (trashed ? "1" : "0");
        }

        static DirectRef decode(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Missing direct item ID");
            }
            String[] parts = value.split("\\.", -1);
            if (parts.length != 3 || !"direct".equals(parts[0])
                    || !("0".equals(parts[2]) || "1".equals(parts[2]))) {
                throw new IllegalArgumentException("Invalid direct item ID");
            }
            return new DirectRef(decodePart(parts[1]), "1".equals(parts[2]));
        }
    }
}
