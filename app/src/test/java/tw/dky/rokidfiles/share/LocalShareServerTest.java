package tw.dky.rokidfiles.share;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tw.dky.rokidfiles.share.MediaAccess.Kind;
import tw.dky.rokidfiles.share.MediaAccess.MediaItem;
import tw.dky.rokidfiles.share.MediaAccess.ReadResource;

/** 以真實 loopback socket 驗證分享伺服器，不依賴 Android Context 或網路框架。 */
public final class LocalShareServerTest {
    private static final InetAddress LOOPBACK = loopback();
    private static final byte[] FILE_BYTES = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String REPOSITORY_ID =
            "repository://private/internal/media-row-42?access=never-expose";
    private static final Pattern JSON_CSRF = Pattern.compile("\\\"csrf\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern JSON_ID = Pattern.compile("\\\"id\\\":\\\"([^\\\"]+)\\\"");

    private final List<RemoteCommandListener.Command> dispatchedCommands =
            new CopyOnWriteArrayList<>();

    private FakeMediaAccess mediaAccess;
    private LocalShareServer server;
    private String host;

    @Before
    public void setUp() throws Exception {
        mediaAccess = new FakeMediaAccess();
        mediaAccess.items.add(MediaItem.basic(
                REPOSITORY_ID,
                "測試照片.jpg",
                "image/jpeg",
                Kind.PHOTO,
                FILE_BYTES.length,
                1_700_000_000_000L
        ));

        int port = findAvailablePort();
        server = new LocalShareServer(
                LOOPBACK,
                port,
                mediaAccess,
                null,
                command -> {
                    dispatchedCommands.add(command);
                    return true;
                }
        );
        server.start();
        host = LOOPBACK.getHostAddress() + ":" + server.getPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void homePageIsServedOnTheExpectedHost() throws Exception {
        HttpResponse response = send("GET", "/", headers("Host", host), null);

        assertEquals(200, response.status);
        assertEquals("text/html; charset=utf-8", response.header("content-type"));
        assertTrue(response.bodyUtf8().contains("<title>眼鏡檔案站</title>"));
        assertEquals("DENY", response.header("x-frame-options"));
    }

    @Test
    public void wrongHostAndCrossOriginPairingAreRejected() throws Exception {
        HttpResponse wrongHost = send(
                "GET",
                "/",
                headers("Host", "attacker.example:" + server.getPort()),
                null
        );
        assertEquals(421, wrongHost.status);

        byte[] pairBody = pairBody(server.getPin());
        HttpResponse wrongOrigin = send(
                "POST",
                "/api/pair",
                headers(
                        "Host", host,
                        "Origin", "http://attacker.example:" + server.getPort(),
                        "Content-Type", "application/json"
                ),
                pairBody
        );
        assertEquals(403, wrongOrigin.status);
    }

    @Test
    public void pinPairingIssuesStrictCookieAndCsrfToken() throws Exception {
        PairCredentials credentials = pair();

        assertTrue(credentials.cookie.startsWith("rk_session="));
        assertTrue(credentials.setCookie.contains("; Path=/"));
        assertTrue(credentials.setCookie.contains("; HttpOnly"));
        assertTrue(credentials.setCookie.contains("; SameSite=Strict"));
        assertTrue(credentials.csrf.matches("[A-Za-z0-9_-]{40,64}"));

        HttpResponse badPin = send(
                "POST",
                "/api/pair",
                headers(
                        "Host", host,
                        "Origin", origin(),
                        "Content-Type", "application/json"
                ),
                pairBody("not-a-pin")
        );
        assertEquals(401, badPin.status);
    }

    @Test
    public void issuedCookieAuthenticatesApiButMissingCookieDoesNot() throws Exception {
        PairCredentials credentials = pair();

        HttpResponse missingCookie = send(
                "GET",
                "/api/files",
                headers("Host", host),
                null
        );
        assertEquals(401, missingCookie.status);

        HttpResponse authenticated = listFiles(credentials);
        assertEquals(200, authenticated.status);
        assertTrue(authenticated.bodyUtf8().contains("測試照片.jpg"));
    }

    @Test
    public void fileListUsesOpaqueIdAndDoesNotLeakRepositoryId() throws Exception {
        PairCredentials credentials = pair();
        HttpResponse response = listFiles(credentials);

        assertEquals(200, response.status);
        assertFalse(response.bodyUtf8().contains(REPOSITORY_ID));
        assertFalse(response.bodyUtf8().contains("media-row-42"));

        String opaqueId = requireJsonValue(JSON_ID, response.bodyUtf8());
        assertTrue(opaqueId.matches("[A-Za-z0-9_-]{20,64}"));
        assertNotEquals(REPOSITORY_ID, opaqueId);
    }

    @Test
    public void readOnlyItemAdvertisesNoMutationAndEveryMutationIsRejectedAtServer()
            throws Exception {
        PairCredentials credentials = pair();
        HttpResponse list = listFiles(credentials);
        String json = list.bodyUtf8();
        String opaqueId = requireJsonValue(JSON_ID, json);

        assertTrue(json.contains("\"canRename\":false"));
        assertTrue(json.contains("\"canTrash\":false"));
        assertTrue(json.contains("\"canRestore\":false"));
        assertTrue(json.contains("\"canFavorite\":false"));
        assertTrue(json.contains("\"canProtect\":false"));

        assertEquals(403, postJson("/api/trash", credentials,
                "{\"id\":\"" + opaqueId + "\"}").status);
        assertEquals(403, postJson("/api/restore", credentials,
                "{\"id\":\"" + opaqueId + "\"}").status);
        assertEquals(403, postJson("/api/rename", credentials,
                "{\"id\":\"" + opaqueId + "\",\"name\":\"new.jpg\"}").status);
        assertEquals(403, postJson("/api/favorite", credentials,
                "{\"id\":\"" + opaqueId + "\",\"value\":true}").status);
        assertEquals(403, postJson("/api/protected", credentials,
                "{\"id\":\"" + opaqueId + "\",\"value\":true}").status);
        assertEquals(0, mediaAccess.mutationCalls);
    }

    @Test
    public void backendFailureMessageCannotLeakSensitivePathIntoJson() throws Exception {
        mediaAccess.items.clear();
        mediaAccess.items.add(new MediaItem(
                REPOSITORY_ID,
                "測試照片.jpg",
                "image/jpeg",
                Kind.PHOTO,
                FILE_BYTES.length,
                1_700_000_000_000L,
                0L,
                0,
                0,
                false,
                false,
                false,
                true,
                true,
                false,
                true,
                true
        ));
        String sensitive = "/storage/emulated/0/DCIM/Camera/私人照片.jpg";
        mediaAccess.renameFailure = new MediaAccess.Failure(
                MediaAccess.Failure.Reason.BUSY,
                "rename failed at " + sensitive,
                new IOException("backend path: " + sensitive)
        );

        PairCredentials credentials = pair();
        String opaqueId = listOpaqueId(credentials);
        HttpResponse response = postJson("/api/rename", credentials,
                "{\"id\":\"" + opaqueId + "\",\"name\":\"new.jpg\"}");

        assertEquals(503, response.status);
        assertFalse(response.bodyUtf8().contains(sensitive));
        assertFalse(response.bodyUtf8().contains("storage/emulated"));
        assertFalse(response.bodyUtf8().contains("私人照片"));
        assertTrue(response.bodyUtf8().contains("媒體操作暫時無法完成"));
        assertEquals(1, mediaAccess.mutationCalls);
    }

    @Test
    public void byteRangeReturns206AndOnlyTheRequestedSlice() throws Exception {
        PairCredentials credentials = pair();
        String opaqueId = listOpaqueId(credentials);

        HttpResponse response = send(
                "GET",
                "/api/file?id=" + opaqueId,
                headers(
                        "Host", host,
                        "Cookie", credentials.cookie,
                        "Range", "bytes=2-5"
                ),
                null
        );

        assertEquals(206, response.status);
        assertEquals("bytes 2-5/" + FILE_BYTES.length, response.header("content-range"));
        assertEquals("bytes", response.header("accept-ranges"));
        assertEquals("4", response.header("content-length"));
        assertArrayEquals(Arrays.copyOfRange(FILE_BYTES, 2, 6), response.body);
        assertEquals(REPOSITORY_ID, mediaAccess.lastOpenedRepositoryId);
        assertEquals(2L, mediaAccess.lastOpenedOffset);
    }

    @Test
    public void mutationWithoutCsrfIsRejectedBeforeMediaAccess() throws Exception {
        PairCredentials credentials = pair();
        String opaqueId = listOpaqueId(credentials);
        byte[] body = ("{\"id\":\"" + opaqueId + "\"}").getBytes(StandardCharsets.UTF_8);

        HttpResponse response = send(
                "POST",
                "/api/trash",
                headers(
                        "Host", host,
                        "Origin", origin(),
                        "Cookie", credentials.cookie,
                        "Content-Type", "application/json"
                ),
                body
        );

        assertEquals(403, response.status);
        assertEquals(0, mediaAccess.trashCalls);
        assertTrue(mediaAccess.items.stream()
                .anyMatch(item -> REPOSITORY_ID.equals(item.getRepositoryId())));
    }

    @Test
    public void remoteCommandsAreDispatchedAfterCookieOriginAndCsrfValidation() throws Exception {
        PairCredentials credentials = pair();
        String[] actions = {"previous", "next", "open", "back"};

        for (String action : actions) {
            HttpResponse response = send(
                    "POST",
                    "/api/remote?action=" + action,
                    mutationHeaders(credentials),
                    null
            );
            assertEquals("action=" + action, 200, response.status);
            assertEquals("{\"ok\":true}", response.bodyUtf8());
        }

        assertEquals(Arrays.asList(
                RemoteCommandListener.Command.PREVIOUS,
                RemoteCommandListener.Command.NEXT,
                RemoteCommandListener.Command.OPEN,
                RemoteCommandListener.Command.BACK
        ), dispatchedCommands);
    }

    private PairCredentials pair() throws Exception {
        HttpResponse response = send(
                "POST",
                "/api/pair",
                headers(
                        "Host", host,
                        "Origin", origin(),
                        "Content-Type", "application/json"
                ),
                pairBody(server.getPin())
        );
        assertEquals(200, response.status);

        String setCookie = response.header("set-cookie");
        assertNotNull(setCookie);
        String cookie = setCookie.split(";", 2)[0];
        String csrf = requireJsonValue(JSON_CSRF, response.bodyUtf8());
        return new PairCredentials(cookie, setCookie, csrf);
    }

    private HttpResponse listFiles(PairCredentials credentials) throws Exception {
        return send(
                "GET",
                "/api/files",
                headers("Host", host, "Cookie", credentials.cookie),
                null
        );
    }

    private String listOpaqueId(PairCredentials credentials) throws Exception {
        HttpResponse response = listFiles(credentials);
        assertEquals(200, response.status);
        return requireJsonValue(JSON_ID, response.bodyUtf8());
    }

    private HttpResponse postJson(
            String path,
            PairCredentials credentials,
            String json
    ) throws IOException {
        Map<String, String> requestHeaders = new LinkedHashMap<>(mutationHeaders(credentials));
        requestHeaders.put("Content-Type", "application/json");
        return send("POST", path, requestHeaders, json.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> mutationHeaders(PairCredentials credentials) {
        return headers(
                "Host", host,
                "Origin", origin(),
                "Cookie", credentials.cookie,
                "X-Rokid-Csrf", credentials.csrf
        );
    }

    private String origin() {
        return "http://" + host;
    }

    private static byte[] pairBody(String pin) {
        return ("{\"pin\":\"" + pin + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private HttpResponse send(
            String method,
            String target,
            Map<String, String> suppliedHeaders,
            byte[] body
    ) throws IOException {
        Map<String, String> requestHeaders = new LinkedHashMap<>(suppliedHeaders);
        requestHeaders.putIfAbsent("Connection", "close");
        if (body != null) {
            requestHeaders.putIfAbsent("Content-Length", Integer.toString(body.length));
        }

        StringBuilder head = new StringBuilder()
                .append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("\r\n");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, server.getPort()), 2_000);
            socket.setSoTimeout(5_000);
            OutputStream output = socket.getOutputStream();
            output.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                output.write(body);
            }
            output.flush();
            socket.shutdownOutput();

            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[4_096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                raw.write(buffer, 0, count);
            }
            return HttpResponse.parse(raw.toByteArray());
        }
    }

    private static Map<String, String> headers(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("headers require name/value pairs");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            result.put(namesAndValues[index], namesAndValues[index + 1]);
        }
        return result;
    }

    private static String requireJsonValue(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        assertTrue("missing JSON value in: " + json, matcher.find());
        return matcher.group(1);
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket candidate = new ServerSocket()) {
            candidate.bind(new InetSocketAddress(LOOPBACK, 0));
            return candidate.getLocalPort();
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class PairCredentials {
        final String cookie;
        final String setCookie;
        final String csrf;

        PairCredentials(String cookie, String setCookie, String csrf) {
            this.cookie = cookie;
            this.setCookie = setCookie;
            this.csrf = csrf;
        }
    }

    private static final class HttpResponse {
        final int status;
        final Map<String, String> headers;
        final byte[] body;

        HttpResponse(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        static HttpResponse parse(byte[] raw) throws IOException {
            int bodyStart = -1;
            for (int index = 0; index <= raw.length - 4; index++) {
                if (raw[index] == '\r' && raw[index + 1] == '\n'
                        && raw[index + 2] == '\r' && raw[index + 3] == '\n') {
                    bodyStart = index + 4;
                    break;
                }
            }
            if (bodyStart < 0) {
                throw new IOException("response headers are incomplete");
            }

            String head = new String(raw, 0, bodyStart - 4, StandardCharsets.ISO_8859_1);
            String[] lines = head.split("\\r\\n");
            String[] statusParts = lines[0].split(" ", 3);
            if (statusParts.length < 2) {
                throw new IOException("response status line is invalid");
            }
            int status;
            try {
                status = Integer.parseInt(statusParts[1]);
            } catch (NumberFormatException invalid) {
                throw new IOException("response status is invalid", invalid);
            }

            Map<String, String> headers = new LinkedHashMap<>();
            for (int index = 1; index < lines.length; index++) {
                int colon = lines[index].indexOf(':');
                if (colon <= 0) {
                    throw new IOException("response header is invalid");
                }
                headers.put(
                        lines[index].substring(0, colon).toLowerCase(Locale.US),
                        lines[index].substring(colon + 1).trim()
                );
            }
            byte[] body = Arrays.copyOfRange(raw, bodyStart, raw.length);
            String contentLength = headers.get("content-length");
            if (contentLength != null
                    && Integer.parseInt(contentLength) != body.length) {
                throw new IOException("response body length does not match Content-Length");
            }
            return new HttpResponse(status, headers, body);
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        String bodyUtf8() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class FakeMediaAccess implements MediaAccess {
        final List<MediaItem> items = Collections.synchronizedList(new ArrayList<>());
        volatile String lastOpenedRepositoryId;
        volatile long lastOpenedOffset = -1L;
        volatile int trashCalls;
        volatile int mutationCalls;
        volatile MediaAccess.Failure renameFailure;

        @Override
        public List<MediaItem> listMedia() {
            synchronized (items) {
                return new ArrayList<>(items);
            }
        }

        @Override
        public ReadResource openMedia(String repositoryId, long offset) throws IOException {
            if (!REPOSITORY_ID.equals(repositoryId) || offset < 0L || offset > FILE_BYTES.length) {
                throw new MediaAccess.Failure(
                        MediaAccess.Failure.Reason.NOT_FOUND,
                        "檔案不存在"
                );
            }
            lastOpenedRepositoryId = repositoryId;
            lastOpenedOffset = offset;
            int start = (int) offset;
            return new ReadResource(
                    new ByteArrayInputStream(FILE_BYTES, start, FILE_BYTES.length - start),
                    "image/jpeg",
                    FILE_BYTES.length - start,
                    FILE_BYTES.length
            );
        }

        @Override
        public ReadResource openThumbnail(String repositoryId, int maxEdgePixels) {
            return null;
        }

        @Override
        public void moveToTrash(String repositoryId) throws IOException {
            trashCalls++;
            mutationCalls++;
            synchronized (items) {
                boolean removed = items.removeIf(item -> repositoryId.equals(item.getRepositoryId()));
                if (!removed) {
                    throw new MediaAccess.Failure(
                            MediaAccess.Failure.Reason.NOT_FOUND,
                            "檔案不存在"
                    );
                }
            }
        }

        @Override
        public MediaItem renameMedia(String repositoryId, String newDisplayName)
                throws IOException {
            mutationCalls++;
            if (renameFailure != null) {
                throw renameFailure;
            }
            throw new MediaAccess.Failure(
                    MediaAccess.Failure.Reason.UNSUPPORTED,
                    "fake 未實作重新命名"
            );
        }

        @Override
        public MediaItem restoreMedia(String repositoryId) throws IOException {
            mutationCalls++;
            throw new MediaAccess.Failure(
                    MediaAccess.Failure.Reason.UNSUPPORTED,
                    "fake 未實作還原"
            );
        }

        @Override
        public MediaItem setFavorite(String repositoryId, boolean favorite) throws IOException {
            mutationCalls++;
            throw new MediaAccess.Failure(
                    MediaAccess.Failure.Reason.UNSUPPORTED,
                    "fake 未實作最愛"
            );
        }

        @Override
        public MediaItem setProtected(String repositoryId, boolean protectedFromTrash)
                throws IOException {
            mutationCalls++;
            throw new MediaAccess.Failure(
                    MediaAccess.Failure.Reason.UNSUPPORTED,
                    "fake 未實作保護"
            );
        }

        @Override
        public MediaItem uploadMedia(
                String displayName,
                String mimeType,
                InputStream body,
                long contentLength
        ) throws IOException {
            throw new MediaAccess.Failure(
                    MediaAccess.Failure.Reason.UNSUPPORTED,
                    "fake 未實作上傳"
            );
        }
    }
}
