package tw.dky.rokidfiles.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import tw.dky.rokidfiles.R;

/**
 * 前景分享服務。USB 模式只監聽 127.0.0.1；Wi-Fi 模式只監聽一個 RFC1918 私有 IPv4，
 * 絕不使用 0.0.0.0。服務閒置十分鐘後自動停止並銷毀本次 session。
 */
public final class ShareService extends Service implements LocalShareServer.Listener {
    public static final String ACTION_START = "tw.dky.rokidfiles.share.START";
    public static final String ACTION_STOP = "tw.dky.rokidfiles.share.STOP";
    public static final String EXTRA_MODE = "tw.dky.rokidfiles.share.MODE";
    public static final String MODE_USB = "usb";
    public static final String MODE_WIFI = "wifi";
    public static final int PORT = 8765;

    private static final String CHANNEL_ID = "local_file_share";
    private static final int NOTIFICATION_ID = 8765;
    private static final long IDLE_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(10L);
    private static final long IDLE_CHECK_INTERVAL_MS = 15_000L;
    private static final long WAKE_LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(11L);
    private static final long REMOTE_COMMAND_TIMEOUT_MS = 2_000L;

    private static volatile MediaAccess.Provider mediaAccessProvider;
    private static volatile WeakReference<RemoteCommandListener> remoteListener =
            new WeakReference<>(null);

    private final LocalBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService startupExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RokidShare-start");
        thread.setDaemon(true);
        return thread;
    });

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            LocalShareServer current = server;
            if (current == null || !current.isRunning()) {
                return;
            }
            if (current.getIdleNanos() >= IDLE_TIMEOUT_NANOS) {
                lastError = "分享已因閒置 10 分鐘而停止";
                stopSharing(true);
                return;
            }
            mainHandler.postDelayed(this, IDLE_CHECK_INTERVAL_MS);
        }
    };

    private volatile LocalShareServer server;
    private volatile String currentMode = MODE_USB;
    private volatile String shareUrl;
    private volatile String visiblePin;
    private volatile String lastError;
    private int generation;
    private PowerManager.WakeLock wakeLock;

    /** 應由 Application 或 Activity 在 start() 前安裝 thread-safe repository factory。 */
    public static void setMediaAccessProvider(MediaAccess.Provider provider) {
        mediaAccessProvider = Objects.requireNonNull(provider, "provider");
    }

    public static void clearMediaAccessProvider(MediaAccess.Provider provider) {
        if (mediaAccessProvider == provider) {
            mediaAccessProvider = null;
        }
    }

    /** Activity 建議於 onStart 呼叫；採 WeakReference 避免忘記解除時洩漏 Activity。 */
    public static void setRemoteCommandListener(RemoteCommandListener listener) {
        remoteListener = new WeakReference<>(Objects.requireNonNull(listener, "listener"));
    }

    /** Activity 建議於 onStop 呼叫。只有目前註冊者會被清除。 */
    public static void clearRemoteCommandListener(RemoteCommandListener listener) {
        RemoteCommandListener current = remoteListener.get();
        if (current == listener) {
            remoteListener = new WeakReference<>(null);
        }
    }

    public static Intent createStartIntent(Context context, String mode) {
        return new Intent(context, ShareService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, MODE_WIFI.equals(mode) ? MODE_WIFI : MODE_USB);
    }

    public static Intent createStopIntent(Context context) {
        return new Intent(context, ShareService.class).setAction(ACTION_STOP);
    }

    /** API 26+ 應從明確使用者動作呼叫；本專案 minSdk 為 28。 */
    public static void start(Context context, String mode) {
        context.startForegroundService(createStartIntent(context, mode));
    }

    public static void stop(Context context) {
        context.startService(createStopIntent(context));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":local-share");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSharing(true);
            return START_NOT_STICKY;
        }
        String requestedMode = intent == null ? MODE_USB : intent.getStringExtra(EXTRA_MODE);
        if (!MODE_WIFI.equals(requestedMode)) {
            requestedMode = MODE_USB;
        }
        beginStart(requestedMode);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void beginStart(String mode) {
        int requestedGeneration = ++generation;
        closeCurrentServer();
        currentMode = mode;
        visiblePin = null;
        shareUrl = null;
        lastError = null;
        startForeground(NOTIFICATION_ID, buildNotification("正在建立本機分享…", true));

        MediaAccess.Provider provider = mediaAccessProvider;
        startupExecutor.execute(() -> {
            LocalShareServer candidate = null;
            try {
                if (provider == null) {
                    throw new IOException("App 尚未安裝 MediaAccess.Provider");
                }
                InetAddress address = MODE_WIFI.equals(mode)
                        ? findPrivateNetworkIpv4() : InetAddress.getByName("127.0.0.1");
                if (address == null) {
                    throw new IOException("找不到可安全監聽的私有 Wi-Fi IPv4");
                }
                MediaAccess access = provider.create(getApplicationContext());
                if (access == null) {
                    throw new IOException("MediaAccess.Provider 回傳 null");
                }
                candidate = new LocalShareServer(address, PORT, access, this,
                        this::dispatchRemoteCommand);
                candidate.start();
                LocalShareServer ready = candidate;
                mainHandler.post(() -> finishStart(requestedGeneration, mode, ready));
            } catch (IOException | RuntimeException error) {
                if (candidate != null) candidate.close();
                String safeError = safeStartupError(error);
                mainHandler.post(() -> failStart(requestedGeneration, safeError));
            }
        });
    }

    private void finishStart(int requestedGeneration, String mode, LocalShareServer ready) {
        if (requestedGeneration != generation) {
            ready.close();
            return;
        }
        server = ready;
        currentMode = mode;
        visiblePin = ready.getPin();
        shareUrl = "http://" + ready.getBindAddress().getHostAddress() + ':' + ready.getPort();
        acquireOrRefreshWakeLock();
        mainHandler.removeCallbacks(idleCheck);
        mainHandler.postDelayed(idleCheck, IDLE_CHECK_INTERVAL_MS);
        String text;
        if (MODE_USB.equals(mode)) {
            text = "USB（推薦）｜PIN " + visiblePin + "｜" + shareUrl;
        } else {
            text = "PIN " + visiblePin + "｜" + shareUrl
                    + "｜僅限個人熱點或可信 Wi-Fi；HTTP 不加密內容";
        }
        notifyForeground(text);
    }

    private void failStart(int requestedGeneration, String error) {
        if (requestedGeneration != generation) {
            return;
        }
        lastError = error;
        visiblePin = null;
        shareUrl = null;
        notifyForeground("無法啟動：" + error);
        mainHandler.postDelayed(() -> {
            if (requestedGeneration == generation && server == null) {
                stopSharing(true);
            }
        }, 6_000L);
    }

    private static String safeStartupError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return "本機分享初始化失敗";
        }
        if (message.contains("MediaAccess") || message.contains("私有 Wi-Fi")
                || message.contains("Address already in use")) {
            return message;
        }
        return "連接埠 8765 無法使用，請停止分享後重試";
    }

    /**
     * 僅選 RFC1918 IPv4，優先 Wi-Fi／熱點介面；排除行動網路、VPN 與虛擬介面。
     * 找不到時回 null，呼叫端必須拒絕 Wi-Fi 分享。
     */
    static InetAddress findPrivateNetworkIpv4() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return null;
        List<AddressCandidate> candidates = new ArrayList<>();
        for (NetworkInterface network : Collections.list(interfaces)) {
            try {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
            } catch (SocketException unavailable) {
                continue;
            }
            String name = network.getName() == null
                    ? "" : network.getName().toLowerCase(Locale.US);
            if (name.startsWith("rmnet") || name.startsWith("ccmni")
                    || name.startsWith("pdp") || name.startsWith("tun")
                    || name.startsWith("ppp") || name.startsWith("dummy")
                    || name.startsWith("docker")) {
                continue;
            }
            int score = interfaceScore(name);
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && isRfc1918((Inet4Address) address)) {
                    candidates.add(new AddressCandidate(address, score, name));
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt((AddressCandidate value) -> value.score).reversed()
                .thenComparing(value -> value.interfaceName)
                .thenComparing(value -> value.address.getHostAddress()));
        return candidates.isEmpty() ? null : candidates.get(0).address;
    }

    private static int interfaceScore(String name) {
        if (name.startsWith("wlan") || name.startsWith("wifi")
                || name.startsWith("swlan") || name.startsWith("ap")
                || name.contains("softap")) return 100;
        if (name.startsWith("eth")) return 50;
        return 10;
    }

    private static boolean isRfc1918(Inet4Address address) {
        byte[] raw = address.getAddress();
        int first = raw[0] & 0xff;
        int second = raw[1] & 0xff;
        return first == 10
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168;
    }

    private boolean dispatchRemoteCommand(RemoteCommandListener.Command command) {
        RemoteCommandListener target = remoteListener.get();
        if (target == null) {
            return false;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return callRemoteListener(target, command);
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean handled = new AtomicBoolean(false);
        mainHandler.post(() -> {
            try {
                RemoteCommandListener current = remoteListener.get();
                if (current == target) {
                    handled.set(callRemoteListener(current, command));
                }
            } finally {
                completed.countDown();
            }
        });
        try {
            return completed.await(REMOTE_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    && handled.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean callRemoteListener(
            RemoteCommandListener listener,
            RemoteCommandListener.Command command
    ) {
        try {
            return listener.onRemoteCommand(command);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public void onAuthenticatedActivity() {
        mainHandler.post(this::acquireOrRefreshWakeLock);
    }

    @Override
    public void onFatalServerError(IOException error) {
        mainHandler.post(() -> {
            if (server != null) {
                lastError = "本機分享連線意外停止";
                stopSharing(true);
            }
        });
    }

    private void acquireOrRefreshWakeLock() {
        PowerManager.WakeLock lock = wakeLock;
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
            lock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            // Wake lock 失敗不應放寬網路安全限制；服務仍由 idle timer 控制。
        }
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock lock = wakeLock;
        if (lock != null && lock.isHeld()) {
            try {
                lock.release();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
    }

    private void closeCurrentServer() {
        LocalShareServer current = server;
        server = null;
        if (current != null) current.close();
        mainHandler.removeCallbacks(idleCheck);
        releaseWakeLock();
    }

    private void stopSharing(boolean stopService) {
        generation++;
        closeCurrentServer();
        visiblePin = null;
        shareUrl = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (stopService) stopSelf();
    }

    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "本機檔案分享",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("顯示本機分享位址、一次性 PIN 與停止控制");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void notifyForeground(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text, false));
    }

    private Notification buildNotification(String text, boolean preparing) {
        Intent stopIntent = createStopIntent(this);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(preparing ? "Rokid眼鏡檔案管理APP準備分享" : "Rokid眼鏡檔案管理APP分享中")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notification),
                        "停止分享",
                        stopPending).build());
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            PendingIntent content = PendingIntent.getActivity(
                    this,
                    2,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(content);
        }
        return builder.build();
    }

    @Override
    public void onDestroy() {
        generation++;
        closeCurrentServer();
        startupExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public final class LocalBinder extends Binder {
        public ShareService getService() {
            return ShareService.this;
        }

        public boolean isRunning() {
            LocalShareServer current = server;
            return current != null && current.isRunning();
        }

        public String getMode() {
            return currentMode;
        }

        public String getShareUrl() {
            return shareUrl;
        }

        public String getPin() {
            return visiblePin;
        }

        public String getLastError() {
            return lastError;
        }

        public void stopSharing() {
            ShareService.this.stopSharing(true);
        }
    }

    private static final class AddressCandidate {
        final InetAddress address;
        final int score;
        final String interfaceName;

        AddressCandidate(InetAddress address, int score, String interfaceName) {
            this.address = address;
            this.score = score;
            this.interfaceName = interfaceName;
        }
    }
}
