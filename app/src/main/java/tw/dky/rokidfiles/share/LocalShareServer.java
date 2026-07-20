package tw.dky.rokidfiles.share;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import tw.dky.rokidfiles.share.MediaAccess.DuplicateGroup;
import tw.dky.rokidfiles.share.MediaAccess.Failure;
import tw.dky.rokidfiles.share.MediaAccess.MediaItem;
import tw.dky.rokidfiles.share.MediaAccess.ReadResource;

/**
 * 僅供 ShareService 使用的小型 HTTP/1.1 伺服器。每個連線只處理一個 request，刻意不支援
 * chunked body、pipeline、WebSocket、CORS 或目錄路徑，縮小攻擊面。
 */
public final class LocalShareServer implements Closeable {
    public interface Listener {
        void onAuthenticatedActivity();

        void onFatalServerError(IOException error);
    }

    public interface RemoteDispatcher {
        boolean dispatch(RemoteCommandListener.Command command);
    }

    private static final int MAX_REQUEST_LINE_BYTES = 4 * 1024;
    private static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_QUERY_BYTES = 4 * 1024;
    private static final int MAX_SMALL_BODY_BYTES = 8 * 1024;
    private static final long MAX_UPLOAD_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final long MAX_THUMB_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_LIST_ENTRIES = 10_000;
    private static final int MAX_OPAQUE_ENTRIES = 20_000;
    private static final int MAX_DUPLICATE_GROUPS = 1_000;
    private static final int MAX_DUPLICATE_ITEMS = 10_000;
    private static final int HEADER_TIMEOUT_MS = 10_000;
    private static final int SMALL_BODY_TIMEOUT_MS = 15_000;
    private static final int BODY_IDLE_TIMEOUT_MS = 30_000;
    private static final long UPLOAD_TOTAL_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long LISTENER_NOTIFY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long LARGE_FILE_DEFAULT_BYTES = 100L * 1024L * 1024L;
    private static final int THUMB_EDGE_PX = 480;
    private static final String SESSION_COOKIE = "rk_session";
    private static final String CSRF_HEADER = "x-rokid-csrf";

    private static final String CSP = "default-src 'none'; style-src 'self'; "
            + "script-src 'self'; img-src 'self' blob:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
            + "form-action 'self'";

    private final InetAddress bindAddress;
    private final int requestedPort;
    private final MediaAccess mediaAccess;
    private final Listener listener;
    private final RemoteDispatcher remoteDispatcher;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String pin;
    private final String sessionToken;
    private final String csrfToken;
    private final PairRateLimiter pairRateLimiter = new PairRateLimiter();
    private final ConcurrentHashMap<String, EntryRef> opaqueToEntry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> repositoryToOpaque = new ConcurrentHashMap<>();
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastListenerNotifyNanos = new AtomicLong(0L);
    private final AtomicLong mediaRevision = new AtomicLong(0L);
    private final AtomicReference<String> authenticatedRemote = new AtomicReference<>();
    private final ThreadPoolExecutor workers;

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean closed;
    private volatile List<DuplicateGroup> duplicateSnapshot;

    public LocalShareServer(
            InetAddress bindAddress,
            int port,
            MediaAccess mediaAccess,
            Listener listener,
            RemoteDispatcher remoteDispatcher
    ) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("invalid port");
        }
        this.requestedPort = port;
        this.mediaAccess = Objects.requireNonNull(mediaAccess, "mediaAccess");
        this.listener = listener;
        this.remoteDispatcher = remoteDispatcher;
        this.pin = String.format(Locale.US, "%06d", secureRandom.nextInt(1_000_000));
        this.sessionToken = randomToken(32); // 256-bit；高於最低 192-bit 要求。
        this.csrfToken = randomToken(32);
        this.workers = new ThreadPoolExecutor(
                2,
                4,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(24),
                new NamedThreadFactory("RokidShare-http"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.workers.allowCoreThreadTimeOut(true);
    }

    public synchronized void start() throws IOException {
        if (closed) {
            throw new IOException("server already closed");
        }
        if (serverSocket != null) {
            return;
        }
        ServerSocket candidate = new ServerSocket();
        try {
            candidate.setReuseAddress(true);
            candidate.bind(new InetSocketAddress(bindAddress, requestedPort), 16);
        } catch (IOException error) {
            closeQuietly(candidate);
            throw error;
        }
        serverSocket = candidate;
        Thread thread = new Thread(this::acceptLoop, "RokidShare-accept");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();
    }

    public String getPin() {
        return pin;
    }

    public InetAddress getBindAddress() {
        return bindAddress;
    }

    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket == null ? requestedPort : socket.getLocalPort();
    }

    public boolean isRunning() {
        ServerSocket socket = serverSocket;
        return !closed && socket != null && !socket.isClosed();
    }

    public long getIdleNanos() {
        return Math.max(0L, System.nanoTime() - lastActivityNanos.get());
    }

    private String randomToken(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void acceptLoop() {
        try {
            while (!closed) {
                ServerSocket listening = serverSocket;
                if (listening == null) {
                    break;
                }
                Socket socket = listening.accept();
                configure(socket);
                openSockets.add(socket);
                try {
                    workers.execute(() -> handleConnection(socket));
                } catch (RejectedExecutionException overloaded) {
                    openSockets.remove(socket);
                    sendBusyAndClose(socket);
                }
            }
        } catch (SocketException error) {
            if (!closed) {
                notifyFatal(error);
            }
        } catch (IOException error) {
            if (!closed) {
                notifyFatal(error);
            }
        }
    }

    private static void configure(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(false);
        socket.setSoTimeout(HEADER_TIMEOUT_MS);
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            ResponseWriter response = new ResponseWriter(socket.getOutputStream());
            try {
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
                Request request = parseRequest(socket, input);
                if (request != null) {
                    dispatch(request, response);
                }
            } catch (HttpProblem problem) {
                if (!response.isCommitted()) {
                    response.sendError(problem.status, problem.safeMessage, problem.extraHeaders);
                }
            } catch (SocketTimeoutException timeout) {
                if (!response.isCommitted()) {
                    response.sendError(408, "要求逾時", Collections.emptyMap());
                }
            } catch (Failure failure) {
                if (!response.isCommitted()) {
                    int status = statusForFailure(failure.getReason());
                    response.sendError(status, safeMessageForFailure(failure.getReason()),
                            Collections.emptyMap());
                }
            } catch (IOException error) {
                if (!response.isCommitted()) {
                    response.sendError(500, "媒體操作失敗", Collections.emptyMap());
                }
            } catch (RuntimeException error) {
                if (!response.isCommitted()) {
                    response.sendError(500, "伺服器無法處理此要求", Collections.emptyMap());
                }
            }
        } catch (IOException ignored) {
            // 客戶端中斷；不記錄 URI、檔名、token 或其他敏感資訊。
        } finally {
            openSockets.remove(socket);
        }
    }

    private void dispatch(Request request, ResponseWriter response) throws IOException, HttpProblem {
        validateHost(request);

        if ("GET".equals(request.method)) {
            switch (request.path) {
                case "/":
                    requireNoBody(request);
                    response.sendBytes(200, "text/html; charset=utf-8", WebAssets.INDEX,
                            Collections.emptyMap());
                    return;
                case "/app.css":
                    requireNoBody(request);
                    response.sendBytes(200, "text/css; charset=utf-8", WebAssets.CSS,
                            Collections.emptyMap());
                    return;
                case "/app.js":
                    requireNoBody(request);
                    response.sendBytes(200, "text/javascript; charset=utf-8", WebAssets.JS,
                            Collections.emptyMap());
                    return;
                case "/favicon.ico":
                    requireNoBody(request);
                    response.sendEmpty(204, Collections.emptyMap());
                    return;
                default:
                    break;
            }
        }

        if ("POST".equals(request.method) && "/api/pair".equals(request.path)) {
            handlePair(request, response);
            return;
        }

        if (!request.path.startsWith("/api/")) {
            throw new HttpProblem(404, "找不到資源");
        }
        requireSession(request);
        if ("POST".equals(request.method)) {
            requireMutationGuards(request);
        }
        markActivity();

        if ("GET".equals(request.method)) {
            switch (request.path) {
                case "/api/files":
                    handleList(request, response);
                    return;
                case "/api/file":
                    handleFile(request, response);
                    return;
                case "/api/thumb":
                    handleThumbnail(request, response);
                    return;
                case "/api/duplicates":
                    requireNoBody(request);
                    handleDuplicateList(response);
                    return;
                default:
                    throw new HttpProblem(404, "找不到 API");
            }
        }

        if ("POST".equals(request.method)) {
            switch (request.path) {
                case "/api/trash":
                    handleTrash(request, response);
                    return;
                case "/api/restore":
                    handleRestore(request, response);
                    return;
                case "/api/rename":
                    handleRename(request, response);
                    return;
                case "/api/favorite":
                    handleToggle(request, response, true);
                    return;
                case "/api/protected":
                    handleToggle(request, response, false);
                    return;
                case "/api/upload":
                    handleUpload(request, response);
                    return;
                case "/api/duplicates/scan":
                    requireNoBody(request);
                    handleDuplicateScan(response);
                    return;
                case "/api/remote":
                    requireNoBody(request);
                    handleRemote(request, response);
                    return;
                default:
                    throw new HttpProblem(404, "找不到 API");
            }
        }
        throw new HttpProblem(405, "不支援此 HTTP 方法", singletonHeader("Allow", "GET, POST"));
    }

    private void handlePair(Request request, ResponseWriter response) throws IOException, HttpProblem {
        if (!"POST".equals(request.method)) {
            throw new HttpProblem(405, "配對只接受 POST");
        }
        requireSameOrigin(request);
        requireJson(request);
        String remote = request.remoteAddress.getHostAddress();
        long retryMillis = pairRateLimiter.retryAfterMillis(remote);
        if (retryMillis > 0L) {
            throw rateLimited(retryMillis);
        }
        Map<String, Object> object = readJsonObject(request, 1_024);
        String candidate = requireJsonString(object, "pin");
        if (candidate.length() != 6 || !constantTimeEquals(candidate, pin)) {
            retryMillis = pairRateLimiter.recordFailure(remote);
            Map<String, String> headers = retryMillis > 0L
                    ? singletonHeader("Retry-After", secondsCeil(retryMillis))
                    : Collections.emptyMap();
            throw new HttpProblem(401, "PIN 不正確", headers);
        }
        String pairedRemote = authenticatedRemote.get();
        if (pairedRemote == null) {
            authenticatedRemote.compareAndSet(null, remote);
            pairedRemote = authenticatedRemote.get();
        }
        if (!remote.equals(pairedRemote)) {
            throw new HttpProblem(409, "本次分享已有一台管理裝置配對");
        }
        pairRateLimiter.recordSuccess(remote);
        markActivity();
        String body = "{\"csrf\":" + jsonString(csrfToken)
                + ",\"expiresOnIdleSeconds\":600}";
        Map<String, String> headers = singletonHeader(
                "Set-Cookie",
                SESSION_COOKIE + "=" + sessionToken + "; Path=/; HttpOnly; SameSite=Strict"
        );
        response.sendJson(200, body, headers);
    }

    private void handleList(Request request, ResponseWriter response) throws IOException, HttpProblem {
        requireNoBody(request);
        Map<String, String> query = parseQuery(request.rawQuery);
        String view = query.getOrDefault("view", "all");
        List<MediaItem> source;
        if ("trash".equals(view)) {
            source = mediaAccess.listTrash();
        } else {
            source = mediaAccess.listMedia();
        }
        if (source == null) {
            throw new IOException("repository returned null list");
        }
        if (source.size() > MAX_LIST_ENTRIES) {
            throw new HttpProblem(413, "檔案數量超過單次清單上限");
        }

        List<MediaItem> filtered = filterItems(
                source, view, query.get("date"), query.get("minBytes"));
        filtered.sort(Comparator.comparingLong(MediaItem::getModifiedEpochMillis).reversed());
        StringBuilder json = new StringBuilder(Math.min(1_048_576, 64 + filtered.size() * 192));
        json.append("{\"view\":").append(jsonString(view)).append(",\"files\":[");
        boolean first = true;
        Set<String> seenRepositoryIds = new HashSet<>();
        for (MediaItem item : filtered) {
            validateRepositoryItem(item);
            if (!seenRepositoryIds.add(item.getRepositoryId())) {
                continue;
            }
            EntryRef ref = register(item);
            if (!first) {
                json.append(',');
            }
            first = false;
            appendItemJson(json, ref.opaqueId, item);
        }
        json.append("]}");
        response.sendJson(200, json.toString(), Collections.emptyMap());
    }

    private List<MediaItem> filterItems(
            List<MediaItem> source,
            String view,
            String dateValue,
            String minBytesValue
    ) throws HttpProblem {
        if ("trash".equals(view) || "all".equals(view)) {
            return new ArrayList<>(source);
        }

        long start = Long.MIN_VALUE;
        long end = Long.MAX_VALUE;
        long minimumBytes = LARGE_FILE_DEFAULT_BYTES;
        if ("today".equals(view)) {
            LocalDate date = LocalDate.now();
            start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } else if ("date".equals(view)) {
            if (dateValue == null || !dateValue.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new HttpProblem(400, "date 必須是 YYYY-MM-DD");
            }
            try {
                LocalDate date = LocalDate.parse(dateValue);
                start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeException invalid) {
                throw new HttpProblem(400, "date 日期無效");
            }
        } else if ("large".equals(view)) {
            if (minBytesValue != null) {
                minimumBytes = parsePositiveLong(minBytesValue, "minBytes");
                if (minimumBytes > MAX_UPLOAD_BYTES) {
                    throw new HttpProblem(400, "minBytes 超過上限");
                }
            }
        } else if (!"favorites".equals(view) && !"protected".equals(view)) {
            throw new HttpProblem(400, "不支援的檢視");
        }

        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : source) {
            boolean include;
            switch (view) {
                case "today":
                case "date":
                    include = item.getModifiedEpochMillis() >= start
                            && item.getModifiedEpochMillis() < end;
                    break;
                case "large":
                    include = item.getSize() >= minimumBytes;
                    break;
                case "favorites":
                    include = item.isFavorite();
                    break;
                case "protected":
                    include = item.isProtectedFromTrash();
                    break;
                default:
                    include = false;
            }
            if (include) {
                result.add(item);
            }
        }
        return result;
    }

    private void handleFile(Request request, ResponseWriter response) throws IOException, HttpProblem {
        requireNoBody(request);
        EntryRef ref = requireEntry(parseQuery(request.rawQuery).get("id"));
        MediaItem item = ref.item;
        Range range = parseRange(request.headers.get("range"), item.getSize());
        long offset = range == null ? 0L : range.start;
        long length = range == null ? item.getSize() : range.length();

        try (ReadResource resource = mediaAccess.openMedia(item.getRepositoryId(), offset)) {
            if (resource == null) {
                throw new Failure(Failure.Reason.NOT_FOUND, "檔案不存在");
            }
            if (resource.getTotalLength() != item.getSize() || resource.getLength() < length) {
                throw new Failure(Failure.Reason.CONFLICT, "檔案已變更，請重新整理清單");
            }
            String mime = safeMime(resource.getMimeType());
            boolean inline = isSafeInlineMime(mime);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept-Ranges", "bytes");
            headers.put("Content-Disposition", contentDisposition(inline ? "inline" : "attachment",
                    item.getDisplayName()));
            int status = 200;
            if (range != null) {
                status = 206;
                headers.put("Content-Range", "bytes " + range.start + "-" + range.end
                        + "/" + item.getSize());
            }
            response.sendStream(status, inline ? mime : "application/octet-stream", length,
                    headers, resource.getInputStream(), this::markActivity);
        }
    }

    private void handleThumbnail(Request request, ResponseWriter response)
            throws IOException, HttpProblem {
        requireNoBody(request);
        EntryRef ref = requireEntry(parseQuery(request.rawQuery).get("id"));
        try (ReadResource resource = mediaAccess.openThumbnail(
                ref.item.getRepositoryId(), THUMB_EDGE_PX)) {
            if (resource == null) {
                throw new HttpProblem(404, "沒有縮圖");
            }
            if (resource.getLength() > MAX_THUMB_BYTES
                    || resource.getLength() != resource.getTotalLength()) {
                throw new HttpProblem(413, "縮圖超過安全限制");
            }
            String mime = safeMime(resource.getMimeType());
            if (!isSafeThumbnailMime(mime)) {
                throw new HttpProblem(415, "不支援此縮圖格式");
            }
            response.sendStream(200, mime, resource.getLength(), Collections.emptyMap(),
                    resource.getInputStream(), this::markActivity);
        }
    }

    private void handleTrash(Request request, ResponseWriter response) throws IOException, HttpProblem {
        Map<String, Object> object = readActionObject(request);
        EntryRef ref = requireEntry(requireJsonString(object, "id"));
        if (ref.item.isProtectedFromTrash()) {
            throw new HttpProblem(423, "此檔案已啟用防誤刪保護");
        }
        requireItemCapability(ref.item.canTrash(), "此檔案不支援移到垃圾桶");
        mediaAccess.moveToTrash(ref.item.getRepositoryId());
        invalidateDuplicateSnapshot();
        removeEntry(ref);
        response.sendJson(200, "{\"ok\":true}", Collections.emptyMap());
    }

    private void handleRestore(Request request, ResponseWriter response)
            throws IOException, HttpProblem {
        Map<String, Object> object = readActionObject(request);
        EntryRef ref = requireEntry(requireJsonString(object, "id"));
        requireItemCapability(ref.item.canRestore(), "此檔案不支援還原");
        MediaItem restored = mediaAccess.restoreMedia(ref.item.getRepositoryId());
        invalidateDuplicateSnapshot();
        validateRepositoryItem(restored);
        updateEntry(ref, restored);
        StringBuilder body = new StringBuilder("{\"ok\":true,\"file\":");
        appendItemJson(body, ref.opaqueId, restored);
        body.append('}');
        response.sendJson(200, body.toString(), Collections.emptyMap());
    }

    private void handleRename(Request request, ResponseWriter response)
            throws IOException, HttpProblem {
        Map<String, Object> object = readActionObject(request);
        EntryRef ref = requireEntry(requireJsonString(object, "id"));
        requireItemCapability(ref.item.canRename(), "此檔案不支援重新命名");
        String name = validateDisplayName(requireJsonString(object, "name"));
        MediaItem renamed = mediaAccess.renameMedia(ref.item.getRepositoryId(), name);
        invalidateDuplicateSnapshot();
        validateRepositoryItem(renamed);
        updateEntry(ref, renamed);
        StringBuilder body = new StringBuilder("{\"ok\":true,\"file\":");
        appendItemJson(body, ref.opaqueId, renamed);
        body.append('}');
        response.sendJson(200, body.toString(), Collections.emptyMap());
    }

    private void handleToggle(Request request, ResponseWriter response, boolean favorite)
            throws IOException, HttpProblem {
        Map<String, Object> object = readActionObject(request);
        EntryRef ref = requireEntry(requireJsonString(object, "id"));
        requireItemCapability(favorite ? ref.item.canFavorite() : ref.item.canProtect(),
                favorite ? "此檔案不支援最愛標記" : "此檔案不支援保護標記");
        boolean value = requireJsonBoolean(object, "value");
        MediaItem updated = favorite
                ? mediaAccess.setFavorite(ref.item.getRepositoryId(), value)
                : mediaAccess.setProtected(ref.item.getRepositoryId(), value);
        invalidateDuplicateSnapshot();
        validateRepositoryItem(updated);
        updateEntry(ref, updated);
        StringBuilder body = new StringBuilder("{\"ok\":true,\"file\":");
        appendItemJson(body, ref.opaqueId, updated);
        body.append('}');
        response.sendJson(200, body.toString(), Collections.emptyMap());
    }

    private void handleUpload(Request request, ResponseWriter response)
            throws IOException, HttpProblem {
        if (request.contentLength < 0L) {
            throw new HttpProblem(411, "上傳必須提供 Content-Length");
        }
        if (request.contentLength > MAX_UPLOAD_BYTES) {
            throw new HttpProblem(413, "檔案超過 4 GB 上限");
        }
        Map<String, String> query = parseQuery(request.rawQuery);
        String name = validateDisplayName(query.get("name"));
        String mime = safeMime(request.headers.get("content-type"));
        if (mime.indexOf(';') >= 0) {
            mime = mime.substring(0, mime.indexOf(';')).trim();
        }
        FixedLengthBody body = new FixedLengthBody(request.input, request.socket,
                request.contentLength, UPLOAD_TOTAL_TIMEOUT_MS, this::markActivity);
        MediaItem uploaded = mediaAccess.uploadMedia(name, mime, body, request.contentLength);
        invalidateDuplicateSnapshot();
        if (body.remaining() != 0L) {
            throw new IOException("repository did not consume complete upload");
        }
        validateRepositoryItem(uploaded);
        EntryRef ref = register(uploaded);
        StringBuilder result = new StringBuilder("{\"ok\":true,\"file\":");
        appendItemJson(result, ref.opaqueId, uploaded);
        result.append('}');
        response.sendJson(201, result.toString(), Collections.emptyMap());
    }

    private void handleDuplicateScan(ResponseWriter response) throws IOException, HttpProblem {
        long expectedRevision = mediaRevision.get();
        List<DuplicateGroup> groups = mediaAccess.scanDuplicates();
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new Failure(Failure.Reason.BUSY, "重複檔掃描已取消");
        }
        validateDuplicateGroups(groups);
        if (mediaRevision.get() != expectedRevision) {
            throw new Failure(
                    Failure.Reason.CONFLICT, "掃描期間檔案已變更，請重新掃描");
        }
        List<DuplicateGroup> completed =
                Collections.unmodifiableList(new ArrayList<>(groups));
        duplicateSnapshot = completed;
        if (closed || mediaRevision.get() != expectedRevision) {
            duplicateSnapshot = null;
            throw new Failure(
                    Failure.Reason.CONFLICT, "掃描期間檔案已變更，請重新掃描");
        }
        sendDuplicateJson(response, completed, true);
    }

    private void handleDuplicateList(ResponseWriter response) throws IOException, HttpProblem {
        List<DuplicateGroup> snapshot = duplicateSnapshot;
        if (snapshot == null) {
            sendDuplicateJson(response, Collections.emptyList(), false);
        } else {
            sendDuplicateJson(response, snapshot, true);
        }
    }

    private void sendDuplicateJson(
            ResponseWriter response,
            List<DuplicateGroup> groups,
            boolean scanned
    ) throws IOException, HttpProblem {
        StringBuilder json = new StringBuilder("{\"scanned\":");
        json.append(scanned).append(",\"groups\":[");
        boolean firstGroup = true;
        for (DuplicateGroup group : groups) {
            if (!firstGroup) {
                json.append(',');
            }
            firstGroup = false;
            json.append("{\"reclaimableBytes\":").append(group.getReclaimableBytes())
                    .append(",\"files\":[");
            boolean firstItem = true;
            for (MediaItem item : group.getItems()) {
                validateRepositoryItem(item);
                EntryRef ref = register(item);
                if (!firstItem) {
                    json.append(',');
                }
                firstItem = false;
                appendItemJson(json, ref.opaqueId, item);
            }
            json.append("]}");
        }
        json.append("]}");
        response.sendJson(200, json.toString(), Collections.emptyMap());
    }

    private void handleRemote(Request request, ResponseWriter response) throws HttpProblem, IOException {
        String action = parseQuery(request.rawQuery).get("action");
        RemoteCommandListener.Command command;
        if ("previous".equals(action)) {
            command = RemoteCommandListener.Command.PREVIOUS;
        } else if ("next".equals(action)) {
            command = RemoteCommandListener.Command.NEXT;
        } else if ("open".equals(action)) {
            command = RemoteCommandListener.Command.OPEN;
        } else if ("back".equals(action)) {
            command = RemoteCommandListener.Command.BACK;
        } else {
            throw new HttpProblem(400, "不支援的遙控動作");
        }
        if (remoteDispatcher == null) {
            throw new HttpProblem(501, "App 尚未接上遙控功能");
        }
        if (!remoteDispatcher.dispatch(command)) {
            throw new HttpProblem(409, "目前畫面無法執行此動作");
        }
        response.sendJson(200, "{\"ok\":true}", Collections.emptyMap());
    }

    private Map<String, Object> readActionObject(Request request) throws IOException, HttpProblem {
        requireJson(request);
        return readJsonObject(request, MAX_SMALL_BODY_BYTES);
    }

    private Map<String, Object> readJsonObject(Request request, int maxBytes)
            throws IOException, HttpProblem {
        if (request.contentLength < 0L) {
            throw new HttpProblem(411, "必須提供 Content-Length");
        }
        if (request.contentLength <= 0L || request.contentLength > maxBytes) {
            throw new HttpProblem(413, "JSON 內容大小無效");
        }
        FixedLengthBody body = new FixedLengthBody(request.input, request.socket,
                request.contentLength, SMALL_BODY_TIMEOUT_MS, null);
        byte[] bytes = readExactly(body, (int) request.contentLength);
        return JsonObjectParser.parse(decodeUtf8(bytes));
    }

    private void requireSession(Request request) throws HttpProblem {
        String pairedRemote = authenticatedRemote.get();
        if (pairedRemote == null
                || !pairedRemote.equals(request.remoteAddress.getHostAddress())) {
            throw new HttpProblem(401, "本次分享已綁定其他管理裝置");
        }
        String cookie = request.headers.get("cookie");
        String candidate = cookieValue(cookie, SESSION_COOKIE);
        if (candidate == null || !constantTimeEquals(candidate, sessionToken)) {
            throw new HttpProblem(401, "尚未配對或連線憑證已失效");
        }
    }

    private void requireMutationGuards(Request request) throws HttpProblem {
        requireSameOrigin(request);
        String csrf = request.headers.get(CSRF_HEADER);
        if (csrf == null || !constantTimeEquals(csrf, csrfToken)) {
            throw new HttpProblem(403, "CSRF 驗證失敗");
        }
    }

    private void requireSameOrigin(Request request) throws HttpProblem {
        String origin = request.headers.get("origin");
        String host = request.headers.get("host");
        if (origin == null || host == null
                || !origin.equalsIgnoreCase("http://" + host)) {
            throw new HttpProblem(403, "要求來源不符");
        }
    }

    private void validateHost(Request request) throws HttpProblem {
        String hostHeader = request.headers.get("host");
        if (hostHeader == null || hostHeader.isEmpty()) {
            throw new HttpProblem(400, "缺少 Host");
        }
        String host = hostHeader;
        int port = 80;
        int colon = hostHeader.lastIndexOf(':');
        if (colon >= 0) {
            if (hostHeader.indexOf(':') != colon) {
                throw new HttpProblem(400, "Host 格式無效");
            }
            host = hostHeader.substring(0, colon);
            try {
                port = Integer.parseInt(hostHeader.substring(colon + 1));
            } catch (NumberFormatException invalid) {
                throw new HttpProblem(400, "Host 連接埠無效");
            }
        }
        boolean hostMatches = host.equals(bindAddress.getHostAddress())
                || (bindAddress.isLoopbackAddress() && "localhost".equalsIgnoreCase(host));
        if (!hostMatches || port != getPort()) {
            throw new HttpProblem(421, "Host 與本機分享位址不符");
        }
    }

    private static void requireJson(Request request) throws HttpProblem {
        String contentType = request.headers.get("content-type");
        if (contentType == null
                || !contentType.toLowerCase(Locale.US).startsWith("application/json")) {
            throw new HttpProblem(415, "Content-Type 必須是 application/json");
        }
    }

    private static void requireNoBody(Request request) throws HttpProblem {
        if (request.contentLength > 0L) {
            throw new HttpProblem(400, "此 API 不接受 request body");
        }
    }

    private EntryRef requireEntry(String opaqueId) throws HttpProblem {
        if (opaqueId == null || !opaqueId.matches("[A-Za-z0-9_-]{20,64}")) {
            throw new HttpProblem(400, "檔案 ID 無效");
        }
        EntryRef ref = opaqueToEntry.get(opaqueId);
        if (ref == null) {
            throw new HttpProblem(404, "檔案不存在或清單已過期");
        }
        return ref;
    }

    private EntryRef register(MediaItem item) throws HttpProblem {
        String repositoryId = item.getRepositoryId();
        String existingOpaque = repositoryToOpaque.get(repositoryId);
        if (existingOpaque != null) {
            EntryRef existing = opaqueToEntry.get(existingOpaque);
            if (existing != null) {
                existing.item = item;
                return existing;
            }
        }
        if (opaqueToEntry.size() >= MAX_OPAQUE_ENTRIES) {
            throw new HttpProblem(503, "本次分享的檔案索引已達上限，請重新啟動分享");
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            String opaque = randomToken(18);
            EntryRef created = new EntryRef(opaque, item);
            if (opaqueToEntry.putIfAbsent(opaque, created) == null) {
                String raced = repositoryToOpaque.putIfAbsent(repositoryId, opaque);
                if (raced == null) {
                    return created;
                }
                opaqueToEntry.remove(opaque, created);
                EntryRef winner = opaqueToEntry.get(raced);
                if (winner != null) {
                    winner.item = item;
                    return winner;
                }
            }
        }
        throw new HttpProblem(503, "無法建立安全檔案索引");
    }

    private void updateEntry(EntryRef ref, MediaItem updated) {
        String previousRepositoryId = ref.item.getRepositoryId();
        repositoryToOpaque.remove(previousRepositoryId, ref.opaqueId);
        ref.item = updated;
        repositoryToOpaque.put(updated.getRepositoryId(), ref.opaqueId);
    }

    private void removeEntry(EntryRef ref) {
        opaqueToEntry.remove(ref.opaqueId, ref);
        repositoryToOpaque.remove(ref.item.getRepositoryId(), ref.opaqueId);
    }

    private static void validateRepositoryItem(MediaItem item) throws IOException {
        if (item == null) {
            throw new IOException("repository returned null item");
        }
        if (item.getRepositoryId().length() > 1_024
                || item.getDisplayName().length() > 512
                || !isWellFormedUnicode(item.getDisplayName())) {
            throw new IOException("repository returned invalid metadata");
        }
    }

    private static void validateDuplicateGroups(List<DuplicateGroup> groups)
            throws IOException, HttpProblem {
        if (groups == null) {
            throw new IOException("repository returned null duplicate list");
        }
        if (groups.size() > MAX_DUPLICATE_GROUPS) {
            throw new HttpProblem(413, "重複檔群組超過單次上限");
        }
        int total = 0;
        for (DuplicateGroup group : groups) {
            if (group == null || group.getItems() == null || group.getItems().size() < 2) {
                throw new IOException("repository returned invalid duplicate group");
            }
            total += group.getItems().size();
            if (total > MAX_DUPLICATE_ITEMS) {
                throw new HttpProblem(413, "重複檔項目超過單次上限");
            }
        }
    }

    private static void appendItemJson(StringBuilder json, String opaqueId, MediaItem item) {
        json.append('{')
                .append("\"id\":").append(jsonString(opaqueId))
                .append(",\"name\":").append(jsonString(item.getDisplayName()))
                .append(",\"mime\":").append(jsonString(safeMime(item.getMimeType())))
                .append(",\"kind\":").append(jsonString(item.getKind().getWireName()))
                .append(",\"size\":").append(item.getSize())
                .append(",\"modified\":").append(item.getModifiedEpochMillis())
                .append(",\"duration\":").append(item.getDurationMillis())
                .append(",\"width\":").append(item.getWidth())
                .append(",\"height\":").append(item.getHeight())
                .append(",\"favorite\":").append(item.isFavorite())
                .append(",\"protected\":").append(item.isProtectedFromTrash())
                .append(",\"trashed\":").append(item.isTrashed())
                .append(",\"canRename\":").append(item.canRename())
                .append(",\"canTrash\":").append(item.canTrash())
                .append(",\"canRestore\":").append(item.canRestore())
                .append(",\"canFavorite\":").append(item.canFavorite())
                .append(",\"canProtect\":").append(item.canProtect())
                .append('}');
    }

    private static void requireItemCapability(boolean supported, String safeMessage)
            throws HttpProblem {
        if (!supported) {
            throw new HttpProblem(403, safeMessage);
        }
    }

    private static String validateDisplayName(String input) throws HttpProblem {
        if (input == null) {
            throw new HttpProblem(400, "缺少檔名");
        }
        String name = Normalizer.normalize(input.trim(), Normalizer.Form.NFC);
        if (name.isEmpty() || name.length() > 180 || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || !isWellFormedUnicode(name)) {
            throw new HttpProblem(400, "檔名無效或過長");
        }
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (value == 0 || value < 0x20 || value == 0x7f) {
                throw new HttpProblem(400, "檔名包含不允許的控制字元");
            }
        }
        return name;
    }

    private static boolean isWellFormedUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    private void markActivity() {
        long now = System.nanoTime();
        lastActivityNanos.set(now);
        if (listener == null) {
            return;
        }
        long previous = lastListenerNotifyNanos.get();
        if (now - previous >= LISTENER_NOTIFY_INTERVAL_NANOS
                && lastListenerNotifyNanos.compareAndSet(previous, now)) {
            listener.onAuthenticatedActivity();
        }
    }

    private void invalidateDuplicateSnapshot() {
        mediaRevision.incrementAndGet();
        duplicateSnapshot = null;
    }

    private void notifyFatal(IOException error) {
        Listener target = listener;
        if (target != null) {
            target.onFatalServerError(error);
        }
    }

    private static int statusForFailure(Failure.Reason reason) {
        switch (reason) {
            case NOT_FOUND:
                return 404;
            case INVALID:
                return 400;
            case CONFLICT:
                return 409;
            case READ_ONLY:
                return 403;
            case NO_SPACE:
                return 507;
            case BUSY:
                return 503;
            case UNSUPPORTED:
                return 501;
            default:
                return 500;
        }
    }

    /** 固定依類型回應安全文案，絕不把 repository 訊息或實體路徑送到網頁端。 */
    private static String safeMessageForFailure(Failure.Reason reason) {
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

    private static Range parseRange(String header, long totalLength) throws HttpProblem {
        if (header == null) {
            return null;
        }
        Map<String, String> rangeErrorHeader = singletonHeader(
                "Content-Range", "bytes */" + totalLength);
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || totalLength <= 0L) {
            throw new HttpProblem(416, "只支援單一 bytes Range", rangeErrorHeader);
        }
        String value = header.substring(6).trim();
        int dash = value.indexOf('-');
        if (dash < 0 || dash != value.lastIndexOf('-')) {
            throw new HttpProblem(416, "Range 格式無效", rangeErrorHeader);
        }
        try {
            long start;
            long end;
            if (dash == 0) {
                long suffix = Long.parseLong(value.substring(1));
                if (suffix <= 0L) {
                    throw new NumberFormatException();
                }
                suffix = Math.min(suffix, totalLength);
                start = totalLength - suffix;
                end = totalLength - 1L;
            } else {
                start = Long.parseLong(value.substring(0, dash));
                end = dash == value.length() - 1
                        ? totalLength - 1L : Long.parseLong(value.substring(dash + 1));
                if (start < 0L || start >= totalLength || end < start) {
                    throw new NumberFormatException();
                }
                end = Math.min(end, totalLength - 1L);
            }
            return new Range(start, end);
        } catch (NumberFormatException invalid) {
            throw new HttpProblem(416, "Range 超出檔案範圍", rangeErrorHeader);
        }
    }

    private static Request parseRequest(Socket socket, BufferedInputStream input)
            throws IOException, HttpProblem {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEADER_TIMEOUT_MS);
        HeaderBudget budget = new HeaderBudget();
        String requestLine = readHttpLine(socket, input, MAX_REQUEST_LINE_BYTES, budget, deadline);
        if (requestLine == null) {
            return null;
        }
        String[] pieces = requestLine.split(" ", -1);
        if (pieces.length != 3 || pieces[0].isEmpty() || pieces[1].isEmpty()
                || !"HTTP/1.1".equals(pieces[2])) {
            throw new HttpProblem(400, "HTTP request line 無效");
        }
        String method = pieces[0];
        if (!isToken(method)) {
            throw new HttpProblem(400, "HTTP 方法無效");
        }
        String target = pieces[1];
        if (!target.startsWith("/") || target.startsWith("//") || target.indexOf('#') >= 0
                || target.length() > MAX_REQUEST_LINE_BYTES) {
            throw new HttpProblem(400, "Request target 無效");
        }

        Map<String, String> headers = new HashMap<>();
        int count = 0;
        while (true) {
            String line = readHttpLine(socket, input, MAX_HEADER_LINE_BYTES, budget, deadline);
            if (line == null) {
                throw new HttpProblem(400, "HTTP headers 未完成");
            }
            if (line.isEmpty()) {
                break;
            }
            if (++count > MAX_HEADER_COUNT || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new HttpProblem(431, "HTTP headers 過多或格式無效");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpProblem(400, "HTTP header 格式無效");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.US);
            String value = trimHttpWhitespace(line.substring(colon + 1));
            if (!isToken(name) || !isSafeHeaderValue(value)) {
                throw new HttpProblem(400, "HTTP header 包含無效字元");
            }
            if (headers.containsKey(name)) {
                throw new HttpProblem(400, "不接受重複 HTTP header");
            }
            headers.put(name, value);
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new HttpProblem(501, "不支援 Transfer-Encoding");
        }
        if (headers.containsKey("expect")) {
            throw new HttpProblem(417, "不支援 Expect header");
        }
        long contentLength = -1L;
        String lengthHeader = headers.get("content-length");
        if (lengthHeader != null) {
            if (!lengthHeader.matches("[0-9]{1,12}")) {
                throw new HttpProblem(400, "Content-Length 無效");
            }
            try {
                contentLength = Long.parseLong(lengthHeader);
            } catch (NumberFormatException invalid) {
                throw new HttpProblem(400, "Content-Length 無效");
            }
            if (contentLength > MAX_UPLOAD_BYTES) {
                throw new HttpProblem(413, "Request body 超過上限");
            }
        }

        int question = target.indexOf('?');
        String path = question < 0 ? target : target.substring(0, question);
        String query = question < 0 ? "" : target.substring(question + 1);
        if (query.length() > MAX_QUERY_BYTES || path.indexOf('%') >= 0 || !isAsciiVisible(path)) {
            throw new HttpProblem(400, "URL 格式無效");
        }
        return new Request(method, path, query, headers, contentLength, input, socket,
                socket.getInetAddress());
    }

    private static String readHttpLine(
            Socket socket,
            InputStream input,
            int lineLimit,
            HeaderBudget budget,
            long deadlineNanos
    ) throws IOException, HttpProblem {
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(lineLimit, 256));
        while (true) {
            int value = timedRead(socket, input, deadlineNanos);
            if (value < 0) {
                if (line.size() == 0) {
                    return null;
                }
                throw new HttpProblem(400, "HTTP line 未完成");
            }
            budget.bytes++;
            if (budget.bytes > MAX_HEADER_BYTES) {
                throw new HttpProblem(431, "HTTP headers 超過上限");
            }
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') {
                    throw new HttpProblem(400, "HTTP line 必須使用 CRLF");
                }
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            if (line.size() >= lineLimit) {
                throw new HttpProblem(431, "HTTP header line 超過上限");
            }
            line.write(value);
        }
    }

    private static int timedRead(Socket socket, InputStream input, long deadlineNanos)
            throws IOException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new SocketTimeoutException("request deadline");
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        socket.setSoTimeout((int) Math.max(1L, Math.min(HEADER_TIMEOUT_MS, millis)));
        return input.read();
    }

    private static Map<String, String> parseQuery(String raw) throws HttpProblem {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        String[] pairs = raw.split("&", -1);
        if (pairs.length > 8) {
            throw new HttpProblem(400, "Query 參數過多");
        }
        for (String pair : pairs) {
            int equal = pair.indexOf('=');
            String rawKey = equal < 0 ? pair : pair.substring(0, equal);
            String rawValue = equal < 0 ? "" : pair.substring(equal + 1);
            String key = decodeQueryComponent(rawKey);
            String value = decodeQueryComponent(rawValue);
            if (key.isEmpty() || values.putIfAbsent(key, value) != null) {
                throw new HttpProblem(400, "Query 參數重複或無效");
            }
        }
        return values;
    }

    private static String decodeQueryComponent(String raw) throws HttpProblem {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (value == '+') {
                bytes.write(' ');
            } else if (value == '%') {
                if (index + 2 >= raw.length()) {
                    throw new HttpProblem(400, "URL percent-encoding 無效");
                }
                int high = hex(raw.charAt(++index));
                int low = hex(raw.charAt(++index));
                if (high < 0 || low < 0) {
                    throw new HttpProblem(400, "URL percent-encoding 無效");
                }
                bytes.write((high << 4) | low);
            } else if (value >= 0x21 && value <= 0x7e && value != '#') {
                bytes.write((byte) value);
            } else {
                throw new HttpProblem(400, "Query 必須使用 UTF-8 percent-encoding");
            }
        }
        try {
            return decodeUtf8(bytes.toByteArray());
        } catch (HttpProblem invalid) {
            throw new HttpProblem(400, "Query UTF-8 無效");
        }
    }

    private static int hex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }

    private static String cookieValue(String cookieHeader, String wanted) {
        if (cookieHeader == null || cookieHeader.length() > MAX_HEADER_LINE_BYTES) {
            return null;
        }
        String found = null;
        for (String part : cookieHeader.split(";")) {
            int equal = part.indexOf('=');
            if (equal <= 0) {
                continue;
            }
            String name = part.substring(0, equal).trim();
            if (wanted.equals(name)) {
                if (found != null) {
                    return null;
                }
                found = part.substring(equal + 1).trim();
            }
        }
        return found;
    }

    private static String requireJsonString(Map<String, Object> object, String key)
            throws HttpProblem {
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw new HttpProblem(400, "JSON 缺少 " + key);
        }
        return (String) value;
    }

    private static boolean requireJsonBoolean(Map<String, Object> object, String key)
            throws HttpProblem {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) {
            throw new HttpProblem(400, "JSON 缺少 " + key);
        }
        return (Boolean) value;
    }

    private static String decodeUtf8(byte[] bytes) throws HttpProblem {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            String value = decoded.toString();
            if (!isWellFormedUnicode(value)) {
                throw new HttpProblem(400, "UTF-8 內容無效");
            }
            return value;
        } catch (CharacterCodingException invalid) {
            throw new HttpProblem(400, "UTF-8 內容無效");
        }
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) {
                throw new EOFException("request body truncated");
            }
            offset += count;
        }
        return result;
    }

    private static long parsePositiveLong(String value, String name) throws HttpProblem {
        try {
            long result = Long.parseLong(value);
            if (result <= 0L) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException invalid) {
            throw new HttpProblem(400, name + " 必須是正整數");
        }
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeMime(String value) {
        if (value == null) {
            return "application/octet-stream";
        }
        String candidate = value.trim().toLowerCase(Locale.US);
        if (candidate.length() > 100
                || !candidate.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+(?:;.*)?")) {
            return "application/octet-stream";
        }
        return candidate;
    }

    private static boolean isSafeInlineMime(String mime) {
        return mime.equals("image/jpeg") || mime.equals("image/png")
                || mime.equals("image/gif") || mime.equals("image/webp")
                || mime.equals("image/bmp") || mime.equals("video/mp4")
                || mime.equals("video/webm") || mime.equals("video/quicktime")
                || mime.equals("video/x-matroska");
    }

    private static boolean isSafeThumbnailMime(String mime) {
        return mime.equals("image/jpeg") || mime.equals("image/png")
                || mime.equals("image/gif") || mime.equals("image/webp")
                || mime.equals("image/bmp");
    }

    private static String contentDisposition(String disposition, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z') || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-'
                    || unsigned == '_' || unsigned == '.') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append("0123456789ABCDEF".charAt(unsigned >>> 4));
                encoded.append("0123456789ABCDEF".charAt(unsigned & 0x0f));
            }
        }
        return disposition + "; filename*=UTF-8''" + encoded;
    }

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16).append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        result.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
            }
        }
        return result.append('"').toString();
    }

    private static String trimHttpWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) start++;
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) end--;
        return value.substring(start, end);
    }

    private static boolean isToken(String value) {
        if (value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            boolean alphaNumeric = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9';
            if (!alphaNumeric && "!#$%&'*+-.^_`|~".indexOf(c) < 0) return false;
        }
        return true;
    }

    private static boolean isSafeHeaderValue(String value) {
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if ((c < 0x20 && c != '\t') || c == 0x7f) return false;
        }
        return true;
    }

    private static boolean isAsciiVisible(String value) {
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < 0x21 || c > 0x7e) return false;
        }
        return true;
    }

    private static HttpProblem rateLimited(long retryMillis) {
        return new HttpProblem(429, "PIN 嘗試次數過多",
                singletonHeader("Retry-After", secondsCeil(retryMillis)));
    }

    private static String secondsCeil(long millis) {
        return Long.toString(Math.max(1L, (millis + 999L) / 1_000L));
    }

    private static Map<String, String> singletonHeader(String name, String value) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(name, value);
        return result;
    }

    private void sendBusyAndClose(Socket socket) {
        try (socket) {
            new ResponseWriter(socket.getOutputStream()).sendError(
                    503, "伺服器忙碌，請稍後再試", singletonHeader("Retry-After", "2"));
        } catch (IOException ignored) {
            // 忽略已中斷連線。
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(serverSocket);
        serverSocket = null;
        for (Socket socket : openSockets) {
            closeQuietly(socket);
        }
        openSockets.clear();
        workers.shutdownNow();
        Thread thread = acceptThread;
        if (thread != null) {
            thread.interrupt();
        }
        opaqueToEntry.clear();
        repositoryToOpaque.clear();
        invalidateDuplicateSnapshot();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static final class Request {
        final String method;
        final String path;
        final String rawQuery;
        final Map<String, String> headers;
        final long contentLength;
        final BufferedInputStream input;
        final Socket socket;
        final InetAddress remoteAddress;

        Request(
                String method,
                String path,
                String rawQuery,
                Map<String, String> headers,
                long contentLength,
                BufferedInputStream input,
                Socket socket,
                InetAddress remoteAddress
        ) {
            this.method = method;
            this.path = path;
            this.rawQuery = rawQuery;
            this.headers = headers;
            this.contentLength = contentLength;
            this.input = input;
            this.socket = socket;
            this.remoteAddress = remoteAddress;
        }
    }

    private static final class EntryRef {
        final String opaqueId;
        volatile MediaItem item;

        EntryRef(String opaqueId, MediaItem item) {
            this.opaqueId = opaqueId;
            this.item = item;
        }
    }

    private static final class Range {
        final long start;
        final long end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long length() {
            return end - start + 1L;
        }
    }

    private static final class HeaderBudget {
        int bytes;
    }

    private static final class HttpProblem extends Exception {
        private static final long serialVersionUID = 1L;

        final int status;
        final String safeMessage;
        final Map<String, String> extraHeaders;

        HttpProblem(int status, String safeMessage) {
            this(status, safeMessage, Collections.emptyMap());
        }

        HttpProblem(int status, String safeMessage, Map<String, String> extraHeaders) {
            this.status = status;
            this.safeMessage = safeMessage;
            this.extraHeaders = extraHeaders;
        }
    }

    private static final class ResponseWriter {
        private final OutputStream output;
        private boolean committed;

        ResponseWriter(OutputStream output) {
            this.output = output;
        }

        boolean isCommitted() {
            return committed;
        }

        void sendJson(int status, String json, Map<String, String> extraHeaders) throws IOException {
            sendBytes(status, "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8), extraHeaders);
        }

        void sendError(int status, String message, Map<String, String> extraHeaders)
                throws IOException {
            sendJson(status, "{\"error\":" + jsonString(message) + "}", extraHeaders);
        }

        void sendEmpty(int status, Map<String, String> extraHeaders) throws IOException {
            writeHead(status, null, 0L, extraHeaders);
            output.flush();
        }

        void sendBytes(
                int status,
                String contentType,
                byte[] body,
                Map<String, String> extraHeaders
        ) throws IOException {
            writeHead(status, contentType, body.length, extraHeaders);
            output.write(body);
            output.flush();
        }

        void sendStream(
                int status,
                String contentType,
                long length,
                Map<String, String> extraHeaders,
                InputStream input,
                Runnable activity
        ) throws IOException {
            writeHead(status, contentType, length, extraHeaders);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0L) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new EOFException("resource truncated");
                }
                output.write(buffer, 0, count);
                remaining -= count;
                if (activity != null) activity.run();
            }
            output.flush();
        }

        private void writeHead(
                int status,
                String contentType,
                long contentLength,
                Map<String, String> extraHeaders
        ) throws IOException {
            if (committed) {
                throw new IOException("response already committed");
            }
            StringBuilder headers = new StringBuilder(512)
                    .append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("Content-Length: ").append(contentLength).append("\r\n")
                    .append("Cache-Control: no-store, max-age=0\r\n")
                    .append("Pragma: no-cache\r\n")
                    .append("X-Content-Type-Options: nosniff\r\n")
                    .append("X-Frame-Options: DENY\r\n")
                    .append("Referrer-Policy: no-referrer\r\n")
                    .append("Cross-Origin-Resource-Policy: same-origin\r\n")
                    .append("Cross-Origin-Opener-Policy: same-origin\r\n")
                    .append("Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n")
                    .append("Content-Security-Policy: ").append(CSP).append("\r\n");
            if (contentType != null) {
                headers.append("Content-Type: ").append(contentType).append("\r\n");
            }
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                if (!isToken(header.getKey()) || !isSafeHeaderValue(header.getValue())) {
                    throw new IOException("unsafe response header");
                }
                headers.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            headers.append("\r\n");
            committed = true;
            output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        }

        private static String reason(int status) {
            switch (status) {
                case 200: return "OK";
                case 201: return "Created";
                case 204: return "No Content";
                case 206: return "Partial Content";
                case 400: return "Bad Request";
                case 401: return "Unauthorized";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 408: return "Request Timeout";
                case 409: return "Conflict";
                case 411: return "Length Required";
                case 413: return "Content Too Large";
                case 415: return "Unsupported Media Type";
                case 416: return "Range Not Satisfiable";
                case 417: return "Expectation Failed";
                case 421: return "Misdirected Request";
                case 423: return "Locked";
                case 429: return "Too Many Requests";
                case 431: return "Request Header Fields Too Large";
                case 500: return "Internal Server Error";
                case 501: return "Not Implemented";
                case 503: return "Service Unavailable";
                case 507: return "Insufficient Storage";
                default: return "Error";
            }
        }
    }

    private static final class FixedLengthBody extends InputStream {
        private final InputStream input;
        private final Socket socket;
        private final long deadlineNanos;
        private final Runnable activity;
        private long remaining;

        FixedLengthBody(
                InputStream input,
                Socket socket,
                long length,
                long totalTimeoutMillis,
                Runnable activity
        ) {
            this.input = input;
            this.socket = socket;
            this.remaining = length;
            this.deadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis);
            this.activity = activity;
        }

        long remaining() {
            return remaining;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > buffer.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (remaining == 0L) {
                return -1;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new SocketTimeoutException("request body total timeout");
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            socket.setSoTimeout((int) Math.max(1L, Math.min(BODY_IDLE_TIMEOUT_MS, millis)));
            int allowed = (int) Math.min(Math.min((long) length, remaining), 64L * 1024L);
            int count = input.read(buffer, offset, allowed);
            if (count < 0) {
                throw new EOFException("request body truncated");
            }
            remaining -= count;
            if (activity != null) activity.run();
            return count;
        }
    }

    private static final class PairRateLimiter {
        private static final int MAX_TRACKED_ADDRESSES = 128;
        private static final long GLOBAL_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(5L);
        private static final long GLOBAL_LOCK_NANOS = TimeUnit.MINUTES.toNanos(10L);
        private final LinkedHashMap<String, Attempt> attempts =
                new LinkedHashMap<>(16, 0.75f, true);
        private long globalWindowStart = System.nanoTime();
        private int globalFailures;
        private long globalBlockedUntil;

        synchronized long retryAfterMillis(String address) {
            long now = System.nanoTime();
            rotateGlobalWindow(now);
            Attempt attempt = attempts.get(address);
            long blockedUntil = Math.max(globalBlockedUntil,
                    attempt == null ? 0L : attempt.blockedUntil);
            return nanosToMillisCeil(blockedUntil - now);
        }

        synchronized long recordFailure(String address) {
            long now = System.nanoTime();
            rotateGlobalWindow(now);
            Attempt attempt = attempts.computeIfAbsent(address, ignored -> new Attempt());
            attempt.failures++;
            long seconds = 1L << Math.min(6, Math.max(0, attempt.failures - 1));
            if (attempt.failures >= 6) {
                seconds = Math.max(seconds, 300L);
            }
            attempt.blockedUntil = now + TimeUnit.SECONDS.toNanos(seconds);
            globalFailures++;
            if (globalFailures >= 20) {
                globalBlockedUntil = now + GLOBAL_LOCK_NANOS;
            }
            trim();
            return retryAfterMillis(address);
        }

        synchronized void recordSuccess(String address) {
            attempts.remove(address);
            if (globalFailures > 0) {
                globalFailures--;
            }
        }

        private void rotateGlobalWindow(long now) {
            if (now - globalWindowStart >= GLOBAL_WINDOW_NANOS) {
                globalWindowStart = now;
                globalFailures = 0;
                if (now >= globalBlockedUntil) {
                    globalBlockedUntil = 0L;
                }
            }
        }

        private void trim() {
            while (attempts.size() > MAX_TRACKED_ADDRESSES) {
                String eldest = attempts.keySet().iterator().next();
                attempts.remove(eldest);
            }
        }

        private static long nanosToMillisCeil(long nanos) {
            if (nanos <= 0L) return 0L;
            return Math.max(1L, (nanos + 999_999L) / 1_000_000L);
        }

        private static final class Attempt {
            int failures;
            long blockedUntil;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger next = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + '-' + next.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class JsonObjectParser {
        private final String input;
        private int position;

        private JsonObjectParser(String input) {
            this.input = input;
        }

        static Map<String, Object> parse(String input) throws HttpProblem {
            JsonObjectParser parser = new JsonObjectParser(input);
            Map<String, Object> result = parser.readObject();
            parser.skipWhitespace();
            if (parser.position != input.length()) {
                throw new HttpProblem(400, "JSON 結尾包含多餘內容");
            }
            return result;
        }

        private Map<String, Object> readObject() throws HttpProblem {
            skipWhitespace();
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (take('}')) return result;
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value;
                if (peek('"')) value = readString();
                else if (peek('[')) value = readStringArray();
                else if (takeWord("true")) value = Boolean.TRUE;
                else if (takeWord("false")) value = Boolean.FALSE;
                else if (takeWord("null")) value = null;
                else throw new HttpProblem(400, "JSON value 類型不支援");
                if (result.containsKey(key)) {
                    throw new HttpProblem(400, "JSON 欄位不可重複");
                }
                result.put(key, value);
                skipWhitespace();
                if (take('}')) return result;
                expect(',');
            }
        }

        private String readString() throws HttpProblem {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < input.length()) {
                char c = input.charAt(position++);
                if (c == '"') {
                    if (!isWellFormedUnicode(result.toString())) {
                        throw new HttpProblem(400, "JSON Unicode 無效");
                    }
                    return result.toString();
                }
                if (c == '\\') {
                    if (position >= input.length()) throw new HttpProblem(400, "JSON escape 無效");
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"': result.append('"'); break;
                        case '\\': result.append('\\'); break;
                        case '/': result.append('/'); break;
                        case 'b': result.append('\b'); break;
                        case 'f': result.append('\f'); break;
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;
                        case 'u': result.append(readUnicodeEscape()); break;
                        default: throw new HttpProblem(400, "JSON escape 無效");
                    }
                } else {
                    if (c < 0x20) throw new HttpProblem(400, "JSON string 包含控制字元");
                    result.append(c);
                }
            }
            throw new HttpProblem(400, "JSON string 未結束");
        }

        private List<String> readStringArray() throws HttpProblem {
            expect('[');
            List<String> result = new ArrayList<>();
            skipWhitespace();
            if (take(']')) return result;
            while (true) {
                skipWhitespace();
                if (!peek('"')) throw new HttpProblem(400, "JSON 陣列只接受字串");
                if (result.size() >= 200) throw new HttpProblem(413, "JSON 陣列項目過多");
                result.add(readString());
                skipWhitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private char readUnicodeEscape() throws HttpProblem {
            if (position + 4 > input.length()) throw new HttpProblem(400, "JSON Unicode escape 無效");
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = hex(input.charAt(position++));
                if (digit < 0) throw new HttpProblem(400, "JSON Unicode escape 無效");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private boolean takeWord(String word) {
            if (input.regionMatches(position, word, 0, word.length())) {
                position += word.length();
                return true;
            }
            return false;
        }

        private boolean peek(char c) {
            return position < input.length() && input.charAt(position) == c;
        }

        private boolean take(char c) {
            if (peek(c)) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char c) throws HttpProblem {
            if (!take(c)) throw new HttpProblem(400, "JSON 格式無效");
        }

        private void skipWhitespace() {
            while (position < input.length()) {
                char c = input.charAt(position);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') position++;
                else return;
            }
        }
    }
}
