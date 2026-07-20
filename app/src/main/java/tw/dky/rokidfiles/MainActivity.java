package tw.dky.rokidfiles;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import tw.dky.rokidfiles.share.GatewayMediaAccess;
import tw.dky.rokidfiles.share.MediaAccess;
import tw.dky.rokidfiles.share.RemoteCommandListener;
import tw.dky.rokidfiles.share.ShareService;
import tw.dky.rokidfiles.storage.CancellationToken;
import tw.dky.rokidfiles.storage.DuplicateScanner;
import tw.dky.rokidfiles.storage.ManagedStorageGateway;
import tw.dky.rokidfiles.storage.MediaItem;
import tw.dky.rokidfiles.storage.MediaPage;
import tw.dky.rokidfiles.storage.MediaQuery;
import tw.dky.rokidfiles.storage.StorageCapacity;
import tw.dky.rokidfiles.storage.StorageCapacityAnalyzer;
import tw.dky.rokidfiles.storage.StorageGateway;
import tw.dky.rokidfiles.storage.StorageOperationResult;

/**
 * 480x640 綠色單色顯示專用主介面。所有功能都可由 DPAD、觸控板與網頁遙控操作。
 */
@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity implements RemoteCommandListener {
    private static final int REQUEST_READ_MEDIA = 100;
    private static final int REQUEST_ALL_FILES = 101;
    private static final int MAX_MEDIA_ITEMS = 10_000;
    private static final long LARGE_FILE_BYTES = 100L * 1024L * 1024L;

    private static final int COLOR_TEXT = Color.rgb(184, 255, 184);
    private static final int COLOR_DIM = Color.rgb(104, 168, 104);
    private static final int COLOR_FOCUS = Color.rgb(23, 61, 23);
    private static final int COLOR_BORDER = Color.rgb(117, 255, 117);

    private enum Screen {
        HOME,
        MEDIA,
        ACTIONS,
        PREVIEW,
        SHARE
    }

    private enum ViewFilter {
        ALL,
        TODAY,
        PHOTOS,
        VIDEOS,
        FAVORITES,
        PROTECTED,
        LARGE,
        DUPLICATES,
        TRASH
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rokid-files-io");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private LinearLayout root;
    private TextView titleView;
    private TextView subtitleView;
    private FrameLayout content;
    private TextView footerView;
    private ListView listView;
    private RowAdapter adapter;
    private ProgressBar progressBar;
    private GestureDetector gestures;
    private boolean busy;

    private final List<UiRow> rows = new ArrayList<>();
    private List<MediaItem> allMedia = Collections.emptyList();
    private List<MediaItem> currentMedia = Collections.emptyList();
    private StorageGateway gateway;
    private MediaItem.Backend gatewayBackend;
    private String gatewayExplanation = "";
    private boolean catalogLoaded;
    private StorageCapacity capacity = StorageCapacity.unknown();
    private Screen screen = Screen.HOME;
    private ViewFilter currentFilter = ViewFilter.ALL;
    private MediaItem activeItem;
    private VideoView activeVideo;
    private Bitmap activeBitmap;
    private Future<?> activeTask;
    private final AtomicLong taskGeneration = new AtomicLong();
    private AtomicBoolean scanCancelled;
    private ShareService.LocalBinder shareBinder;
    private boolean shareBindRequested;
    private boolean shareRequested;
    private boolean shareWifi;
    private int sharePollAttempts;
    private String renderedShareKey;
    private final Runnable shareStatusPoll = this::renderShareStatus;

    private final MediaAccess.Provider mediaAccessProvider = applicationContext -> {
        StorageCoordinator.Selection selection = StorageCoordinator.select(applicationContext);
        if (selection.getGateway() == null) {
            throw new IOException("尚未允許檔案管理");
        }
        return new GatewayMediaAccess(selection.getGateway());
    };

    private final ServiceConnection shareConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof ShareService.LocalBinder) {
                shareBinder = (ShareService.LocalBinder) service;
                renderShareStatus();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            shareBinder = null;
            shareBindRequested = false;
            mainHandler.removeCallbacks(shareStatusPoll);
            if (screen == Screen.SHARE) {
                shareRequested = false;
                subtitleView.setText("本機分享服務已停止");
                showRows(Arrays.asList(
                        actionRow("重新啟動", "建立新的 PIN", () ->
                                startShareService(shareWifi)),
                        actionRow("返回首頁", "", MainActivity.this::showHome)));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildChrome();
        buildGestures();
        ShareService.setMediaAccessProvider(mediaAccessProvider);
        refreshGateway();
        showHome();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerShareRemoteListener();
        refreshGateway();
        if (shareRequested && screen == Screen.SHARE) {
            bindShareService();
        }
    }

    @Override
    protected void onStop() {
        unregisterShareRemoteListener();
        mainHandler.removeCallbacks(shareStatusPoll);
        unbindShareService();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelActiveTask();
        releasePreviewMedia();
        mainHandler.removeCallbacksAndMessages(null);
        ShareService.clearMediaAccessProvider(mediaAccessProvider);
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildChrome() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(18), dp(12), dp(18), dp(10));

        titleView = text(28, COLOR_TEXT, Typeface.BOLD);
        titleView.setMaxLines(1);
        root.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        subtitleView = text(15, COLOR_DIM, Typeface.NORMAL);
        subtitleView.setMaxLines(2);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(2);
        subtitleParams.bottomMargin = dp(8);
        root.addView(subtitleView, subtitleParams);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        footerView = text(13, COLOR_DIM, Typeface.NORMAL);
        footerView.setGravity(Gravity.CENTER_HORIZONTAL);
        footerView.setText("滑動選擇  •  點按開啟  •  雙擊返回");
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(8);
        root.addView(footerView, footerParams);
        setContentView(root);
    }

    private void buildGestures() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                activateSelected();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                navigateBack();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent event) {
                if (screen == Screen.MEDIA) {
                    activateSelected();
                }
            }

            @Override
            public boolean onFling(
                    MotionEvent first,
                    MotionEvent second,
                    float velocityX,
                    float velocityY) {
                float primary = Math.abs(velocityX) >= Math.abs(velocityY)
                        ? velocityX : -velocityY;
                stepSelection(primary < 0f ? 1 : -1);
                return true;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (gestures != null && gestures.onTouchEvent(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            float horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            float vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float value = Math.abs(horizontal) > Math.abs(vertical) ? horizontal : vertical;
            if (value != 0f) {
                stepSelection(value < 0f ? 1 : -1);
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                stepSelection(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                stepSelection(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                activateSelected();
                return true;
            case KeyEvent.KEYCODE_BACK:
                navigateBack();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onRemoteCommand(RemoteCommandListener.Command command) {
        if (busy && command != RemoteCommandListener.Command.BACK) {
            return false;
        }
        switch (command) {
            case PREVIOUS:
                if (screen == Screen.PREVIEW) return false;
                stepSelection(-1);
                return true;
            case NEXT:
                if (screen == Screen.PREVIEW) return false;
                stepSelection(1);
                return true;
            case OPEN:
                if (screen == Screen.PREVIEW && activeVideo == null) return false;
                activateSelected();
                return true;
            case BACK:
                navigateBack();
                return true;
            default:
                return false;
        }
    }

    private void refreshGateway() {
        StorageCoordinator.Selection selection = StorageCoordinator.select(this);
        StorageGateway selected = selection.getGateway();
        MediaItem.Backend selectedBackend = selected == null ? null : selected.getBackend();
        if (selectedBackend != gatewayBackend) {
            allMedia = Collections.emptyList();
            capacity = StorageCapacity.unknown();
            catalogLoaded = false;
        }
        gateway = selected;
        gatewayBackend = selectedBackend;
        gatewayExplanation = selection.getExplanation();
    }

    private void showHome() {
        cancelActiveTask();
        screen = Screen.HOME;
        activeItem = null;
        activeVideo = null;
        titleView.setText("眼鏡檔案站");
        subtitleView.setText(homeSubtitle());
        List<UiRow> home = new ArrayList<>();

        if (gateway == null) {
            home.add(actionRow("允許檔案管理", "一次授權，限定媒體白名單", this::requestAllFilesAccess));
            home.add(actionRow("唯讀瀏覽", "若系統不允許完整檔案管理", this::requestReadPermission));
        } else {
            home.add(actionRow("今日拍攝", "最快找到剛拍內容", () -> showMedia(ViewFilter.TODAY)));
            home.add(actionRow("所有媒體", "依日期分組", () -> showMedia(ViewFilter.ALL)));
            home.add(actionRow("相片", "只看圖片", () -> showMedia(ViewFilter.PHOTOS)));
            home.add(actionRow("影片", "只看影片", () -> showMedia(ViewFilter.VIDEOS)));
            if (managedGateway() != null) {
                home.add(actionRow("已保護", "避免誤刪的重要檔案",
                        () -> showMedia(ViewFilter.PROTECTED)));
                home.add(actionRow("我的最愛", "快速收藏",
                        () -> showMedia(ViewFilter.FAVORITES)));
            }
            home.add(actionRow("大檔案", "100 MB 以上", () -> showMedia(ViewFilter.LARGE)));
            home.add(actionRow("重複檔案", "充電時建議掃描", this::startDuplicateScan));
            home.add(actionRow("垃圾桶", "還原已移除項目", () -> showMedia(ViewFilter.TRASH)));
            home.add(actionRow("USB 電腦管理", "推薦・本機連線", () -> startShare(false)));
            home.add(actionRow("Wi‑Fi 手機管理", "限私人熱點／可信網路", () -> startShare(true)));
            home.add(actionRow("重新整理", "更新檔案與剩餘空間", this::refreshCatalog));
        }
        showRows(home);
        if (gateway != null && !catalogLoaded) {
            refreshCatalog();
        }
    }

    private String homeSubtitle() {
        if (gateway == null) {
            return gatewayExplanation;
        }
        if (capacity.getFreeBytes() >= 0L) {
            String result = gatewayExplanation + "  •  可用 " + UiFormat.bytes(capacity.getFreeBytes());
            if (capacity.hasVideoEstimate()) {
                result += "  •  約可錄 " + capacity.getRemainingVideoMinutes() + " 分";
            }
            return result;
        }
        return gatewayExplanation;
    }

    private void refreshCatalog() {
        if (gateway == null) {
            return;
        }
        runTask("讀取媒體…", task -> {
            List<MediaItem> items = loadAllMedia(false);
            StorageCapacity measured;
            try {
                measured = StorageCapacityAnalyzer.analyzeDefault(gateway);
            } catch (IOException failure) {
                measured = gateway.getCapacitySummary();
            }
            StorageCapacity finalMeasured = measured;
            task.post(() -> {
                allMedia = items;
                capacity = finalMeasured;
                catalogLoaded = true;
                if (screen == Screen.HOME) {
                    subtitleView.setText(homeSubtitle());
                }
            });
        });
    }

    private void showMedia(ViewFilter filter) {
        if (gateway == null) {
            showHome();
            return;
        }
        currentFilter = filter;
        screen = Screen.MEDIA;
        titleView.setText(filterTitle(filter));
        subtitleView.setText("讀取中…");
        runTask("讀取檔案…", task -> {
            List<MediaItem> source = filter == ViewFilter.TRASH
                    ? loadAllMedia(true)
                    : loadAllMedia(false);
            List<MediaItem> filtered = filter(source, filter);
            task.post(() -> {
                if (filter != ViewFilter.TRASH) {
                    allMedia = source;
                }
                renderMedia(filtered, filter);
            });
        });
    }

    private List<MediaItem> loadAllMedia(boolean trash) throws IOException {
        if (trash) {
            List<MediaItem> result = new ArrayList<>();
            int offset = 0;
            while (result.size() < MAX_MEDIA_ITEMS) {
                MediaPage page = gateway.list(MediaQuery.trash(offset, MediaQuery.MAX_PAGE_SIZE));
                result.addAll(page.getItems());
                if (!page.hasMore() || page.getNextOffset() <= offset) {
                    break;
                }
                offset = page.getNextOffset();
            }
            sortNewest(result);
            return Collections.unmodifiableList(result);
        }

        List<MediaItem> result = new ArrayList<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add("<root>");
        visited.add("<root>");
        while (!pending.isEmpty() && result.size() < MAX_MEDIA_ITEMS) {
            String marker = pending.removeFirst();
            String parentId = "<root>".equals(marker) ? null : marker;
            int offset = 0;
            while (result.size() < MAX_MEDIA_ITEMS) {
                MediaPage page = gateway.list(new MediaQuery(
                        parentId, offset, MediaQuery.MAX_PAGE_SIZE, false));
                for (MediaItem item : page.getItems()) {
                    if (item.getKind() == MediaItem.Kind.DIRECTORY) {
                        if (visited.add(item.getId())) {
                            pending.addLast(item.getId());
                        }
                    } else {
                        result.add(item);
                    }
                }
                if (!page.hasMore() || page.getNextOffset() <= offset) {
                    break;
                }
                offset = page.getNextOffset();
            }
        }
        sortNewest(result);
        return Collections.unmodifiableList(result);
    }

    private static void sortNewest(List<MediaItem> items) {
        items.sort(Comparator.comparingLong(MediaItem::getLastModifiedMillis)
                .reversed()
                .thenComparing(MediaItem::getDisplayName));
    }

    private List<MediaItem> filter(List<MediaItem> source, ViewFilter filter) {
        long today = startOfToday();
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : source) {
            boolean include;
            switch (filter) {
                case TODAY:
                    include = item.getLastModifiedMillis() >= today;
                    break;
                case PHOTOS:
                    include = item.getKind() == MediaItem.Kind.IMAGE;
                    break;
                case VIDEOS:
                    include = item.getKind() == MediaItem.Kind.VIDEO;
                    break;
                case FAVORITES:
                    include = item.isFavorite();
                    break;
                case PROTECTED:
                    include = item.isProtected();
                    break;
                case LARGE:
                    include = item.getSizeBytes() >= LARGE_FILE_BYTES;
                    break;
                case DUPLICATES:
                    include = item.getDuplicateGroup() != null;
                    break;
                case TRASH:
                case ALL:
                default:
                    include = true;
                    break;
            }
            if (include) {
                result.add(item);
            }
        }
        if (filter == ViewFilter.LARGE) {
            result.sort(Comparator.comparingLong(MediaItem::getSizeBytes).reversed());
        }
        return result;
    }

    private void renderMedia(List<MediaItem> media, ViewFilter filter) {
        currentMedia = Collections.unmodifiableList(new ArrayList<>(media));
        subtitleView.setText(media.size() + " 個項目" + filterSubtitle(filter));
        List<UiRow> mediaRows = new ArrayList<>();
        String previousGroup = null;
        for (MediaItem item : media) {
            String group = dateGroup(item.getLastModifiedMillis());
            if (!group.equals(previousGroup)) {
                mediaRows.add(headerRow(group));
                previousGroup = group;
            }
            StringBuilder detail = new StringBuilder();
            detail.append(item.getKind() == MediaItem.Kind.VIDEO ? "影片" : "相片")
                    .append("  •  ").append(UiFormat.bytes(item.getSizeBytes()))
                    .append("  •  ").append(UiFormat.dateTime(item.getLastModifiedMillis()));
            if (item.isProtected()) {
                detail.append("  •  已保護");
            } else if (item.isFavorite()) {
                detail.append("  •  最愛");
            }
            mediaRows.add(actionRow(item.getDisplayName(), detail.toString(),
                    () -> showActions(item)));
        }
        if (mediaRows.isEmpty()) {
            mediaRows.add(actionRow("沒有符合項目", "雙擊返回", this::navigateBack));
        }
        showRows(mediaRows);
    }

    private String filterSubtitle(ViewFilter filter) {
        if (filter == ViewFilter.DUPLICATES) {
            return "  •  同組內容完全相同";
        }
        return "";
    }

    private void showActions(MediaItem item) {
        activeItem = item;
        screen = Screen.ACTIONS;
        titleView.setText(ellipsize(item.getDisplayName(), 24));
        subtitleView.setText(UiFormat.bytes(item.getSizeBytes()) + "  •  "
                + UiFormat.dateTime(item.getLastModifiedMillis()));
        List<UiRow> actions = new ArrayList<>();
        actions.add(actionRow("預覽", item.getKind() == MediaItem.Kind.VIDEO
                ? "點按播放／暫停" : "採樣載入，不讀完整原圖", () -> showPreview(item)));
        if (managedGateway() != null) {
            actions.add(actionRow(item.isFavorite() ? "取消最愛" : "加入最愛", "本機標記",
                    () -> toggleFavorite(item)));
            actions.add(actionRow(item.isProtected() ? "解除保護" : "設為保護",
                    item.isProtected() ? "解除後才能更名或移除" : "防止誤刪與誤改",
                    () -> toggleProtected(item)));
        }
        if (item.isTrashed()) {
            if (item.hasCapability(MediaItem.CAPABILITY_RESTORE)) {
                actions.add(actionRow("還原", "回到原位置", () -> restore(item)));
            }
        } else {
            if (item.hasCapability(MediaItem.CAPABILITY_RENAME)) {
                actions.add(actionRow("依日期重新命名", "不需輸入鍵盤",
                        () -> renameByDate(item)));
            }
            if (item.hasCapability(MediaItem.CAPABILITY_TRASH)) {
                actions.add(actionRow("移到垃圾桶", "可復原，不會直接永久刪除",
                        () -> confirmTrash(item)));
            }
        }
        actions.add(actionRow("返回清單", "", this::returnToMedia));
        showRows(actions);
    }

    private void showPreview(MediaItem item) {
        cancelActiveTask();
        releasePreviewMedia();
        screen = Screen.PREVIEW;
        titleView.setText(ellipsize(item.getDisplayName(), 24));
        subtitleView.setText(item.getKind() == MediaItem.Kind.VIDEO
                ? "點按播放／暫停  •  雙擊返回"
                : "縮圖預覽  •  雙擊返回");
        content.removeAllViews();
        if (item.getKind() == MediaItem.Kind.VIDEO) {
            VideoView video = new VideoView(this);
            video.setBackgroundColor(Color.BLACK);
            video.setVideoURI(item.getUri());
            video.setOnPreparedListener(player -> {
                hideProgressOverlay();
                player.setLooping(false);
                video.start();
            });
            video.setOnErrorListener((player, what, extra) -> {
                hideProgressOverlay();
                toast("眼鏡播放器不支援此編碼，請用手機網頁預覽");
                return true;
            });
            content.addView(video, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showProgressOverlay();
            activeVideo = video;
            return;
        }

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setBackgroundColor(Color.BLACK);
        content.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showProgressOverlay();
        activeVideo = null;
        runTask(null, task -> {
            try {
                Bitmap bitmap = gateway.loadThumbnail(item.getId(), 440, 560);
                task.post(() -> {
                    hideProgressOverlay();
                    if (screen == Screen.PREVIEW && activeItem == item) {
                        if (bitmap == null) {
                            toast("此格式無法建立縮圖，請用手機／電腦開啟");
                        } else {
                            activeBitmap = bitmap;
                            image.setImageBitmap(bitmap);
                        }
                    } else if (bitmap != null) {
                        bitmap.recycle();
                    }
                });
            } catch (IOException | RuntimeException failure) {
                task.post(() -> {
                    hideProgressOverlay();
                    if (screen == Screen.PREVIEW && activeItem == item) {
                        toast("無法建立預覽：" + safeMessage(failure));
                    }
                });
            }
        });
    }

    private void toggleFavorite(MediaItem item) {
        ManagedStorageGateway managed = managedGateway();
        if (managed == null) {
            toast("目前後端不支援標記");
            return;
        }
        if (!managed.setFavorite(item, !item.isFavorite())) {
            toast("最愛標記未能保存");
            return;
        }
        reloadActiveItem(item.getId());
    }

    private void toggleProtected(MediaItem item) {
        ManagedStorageGateway managed = managedGateway();
        if (managed == null) {
            toast("目前後端不支援保護標記");
            return;
        }
        if (!managed.setProtected(item, !item.isProtected())) {
            toast("保護標記未能保存");
            return;
        }
        reloadActiveItem(item.getId());
    }

    private ManagedStorageGateway managedGateway() {
        return gateway instanceof ManagedStorageGateway ? (ManagedStorageGateway) gateway : null;
    }

    private void reloadActiveItem(String id) {
        runTask("更新…", task -> {
            try {
                MediaItem updated = gateway.getItem(id);
                task.post(() -> showActions(updated));
            } catch (IOException | RuntimeException failure) {
                task.post(() -> {
                    toast("更新失敗：" + safeMessage(failure));
                    returnToMedia();
                });
            }
        });
    }

    private void renameByDate(MediaItem item) {
        if (!item.hasCapability(MediaItem.CAPABILITY_RENAME)) {
            toast("此檔案不支援重新命名");
            return;
        }
        if (item.isProtected()) {
            toast("請先解除保護");
            return;
        }
        String oldName = item.getDisplayName();
        int dot = oldName.lastIndexOf('.');
        String extension = dot > 0 ? oldName.substring(dot).toLowerCase(Locale.ROOT) : "";
        long time = item.getLastModifiedMillis() > 0L
                ? item.getLastModifiedMillis() : System.currentTimeMillis();
        String base = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                .format(time);
        mutate("重新命名…", () -> gateway.rename(item.getId(), base + extension));
    }

    private void confirmTrash(MediaItem item) {
        if (!item.hasCapability(MediaItem.CAPABILITY_TRASH)) {
            toast("此檔案不支援移到垃圾桶");
            return;
        }
        if (item.isProtected()) {
            toast("請先解除保護");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("移到垃圾桶？")
                .setMessage(item.getDisplayName() + "\n可稍後還原，不會永久刪除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移到垃圾桶", (dialog, which) ->
                        mutate("移動中…", () -> gateway.moveToTrash(item.getId())))
                .show();
    }

    private void restore(MediaItem item) {
        if (!item.hasCapability(MediaItem.CAPABILITY_RESTORE)) {
            toast("此檔案不支援還原");
            return;
        }
        mutate("還原中…", () -> gateway.restore(item.getId()));
    }

    private interface Mutation {
        StorageOperationResult run();
    }

    private void mutate(String message, Mutation mutation) {
        runTask(message, task -> {
            StorageOperationResult result = mutation.run();
            task.post(() -> {
                if (result.isSuccess()) {
                    toast("完成");
                    showMedia(currentFilter);
                } else {
                    toast(result.getMessage().isEmpty()
                            ? "操作失敗：" + result.getStatus() : result.getMessage());
                }
            });
        });
    }

    private void startDuplicateScan() {
        if (gateway == null) {
            return;
        }
        boolean charging = isCharging();
        if (!charging) {
            new AlertDialog.Builder(this)
                    .setTitle("目前未充電")
                    .setMessage("重複檔掃描會讀取大型影片，可能增加耗電與溫度。仍要開始嗎？")
                    .setNegativeButton("稍後", null)
                    .setPositiveButton("手動開始", (dialog, which) -> runDuplicateScan())
                    .show();
            return;
        }
        runDuplicateScan();
    }

    private void runDuplicateScan() {
        cancelActiveTask();
        long generation = taskGeneration.incrementAndGet();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        scanCancelled = cancelled;
        screen = Screen.MEDIA;
        titleView.setText("重複檔掃描");
        subtitleView.setText("準備中…  •  雙擊可取消");
        showRows(Collections.singletonList(actionRow(
                "取消掃描", "已完成的雜湊不會修改原檔", () -> cancelled.set(true))));
        activeTask = ioExecutor.submit(() -> {
            try {
                DuplicateScanner scanner = new DuplicateScanner(gateway);
                DuplicateScanner.Result result = scanner.scan(
                        DuplicateScanner.Options.defaults(),
                        cancelled::get,
                        progress -> postIfTaskCurrent(generation, () -> {
                            if (progress.getPhase() == DuplicateScanner.Phase.HASHING) {
                                subtitleView.setText("比對 " + progress.getFilesProcessed()
                                        + " / " + progress.getCandidateFiles()
                                        + "  •  " + UiFormat.bytes(progress.getBytesProcessed()));
                            } else {
                                subtitleView.setText("尋找候選檔案 " + progress.getFilesProcessed());
                            }
                        }));
                postIfTaskCurrent(generation, () -> {
                    activeTask = null;
                    scanCancelled = null;
                    if (result.isCancelled()) {
                        toast("掃描已取消");
                        showHome();
                    } else {
                        toast("找到 " + result.getGroups().size() + " 組重複檔");
                        showMedia(ViewFilter.DUPLICATES);
                    }
                });
            } catch (IOException | RuntimeException failure) {
                postIfTaskCurrent(generation, () -> {
                    activeTask = null;
                    scanCancelled = null;
                    toast("掃描失敗：" + safeMessage(failure));
                    showHome();
                });
            }
        });
    }

    private boolean isCharging() {
        BatteryManager battery = getSystemService(BatteryManager.class);
        return battery != null && battery.isCharging();
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestReadPermission();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQUEST_ALL_FILES);
        } catch (RuntimeException missingOemPage) {
            Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            try {
                startActivityForResult(fallback, REQUEST_ALL_FILES);
            } catch (RuntimeException noSettingsPage) {
                toast("系統未提供檔案管理授權頁，請到系統設定手動允許");
            }
        }
    }

    private void requestReadPermission() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            refreshGateway();
            showHome();
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_MEDIA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ALL_FILES) {
            refreshGateway();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && !Environment.isExternalStorageManager()) {
                toast("尚未允許檔案管理，可先使用唯讀瀏覽");
            }
            showHome();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_READ_MEDIA) {
            refreshGateway();
            showHome();
        }
    }

    private void startShare(boolean wifi) {
        if (gateway == null) {
            toast("請先允許檔案管理");
            return;
        }
        startShareService(wifi);
    }

    private void startShareService(boolean wifi) {
        shareRequested = true;
        shareWifi = wifi;
        sharePollAttempts = 0;
        renderedShareKey = null;
        mainHandler.removeCallbacks(shareStatusPoll);
        screen = Screen.SHARE;
        titleView.setText(wifi ? "Wi‑Fi 手機管理" : "USB 電腦管理");
        subtitleView.setText("正在建立一次性連線…");
        showRows(Collections.singletonList(actionRow(
                "停止分享", "可隨時中斷所有連線", this::stopShareService)));
        try {
            ShareService.start(this, wifi ? ShareService.MODE_WIFI : ShareService.MODE_USB);
            bindShareService();
            mainHandler.postDelayed(shareStatusPoll, 250L);
        } catch (RuntimeException failure) {
            shareRequested = false;
            toast("分享啟動失敗：" + safeMessage(failure));
            showHome();
        }
    }

    private void registerShareRemoteListener() {
        ShareService.setRemoteCommandListener(this);
    }

    private void unregisterShareRemoteListener() {
        ShareService.clearRemoteCommandListener(this);
    }

    private void bindShareService() {
        if (shareBindRequested) return;
        Intent intent = new Intent(this, ShareService.class);
        try {
            shareBindRequested = bindService(intent, shareConnection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException failure) {
            shareBindRequested = false;
        }
    }

    private void unbindShareService() {
        if (!shareBindRequested) return;
        try {
            unbindService(shareConnection);
        } catch (IllegalArgumentException ignored) {
            // OEM 若已先中斷服務，視為完成解除。
        }
        shareBindRequested = false;
        shareBinder = null;
    }

    private void renderShareStatus() {
        if (!shareRequested || screen != Screen.SHARE) return;
        ShareService.LocalBinder binder = shareBinder;
        String error = binder == null ? null : binder.getLastError();
        String url = binder == null ? null : binder.getShareUrl();
        String pin = binder == null ? null : binder.getPin();
        boolean running = binder != null && binder.isRunning();
        List<UiRow> shareRows = new ArrayList<>();
        if (error != null) {
            renderedShareKey = null;
            subtitleView.setText("無法啟動：" + error);
            shareRows.add(actionRow("重試", "重新建立一次性 PIN", () ->
                    startShareService(shareWifi)));
        } else if (url != null && pin != null && !running) {
            renderedShareKey = null;
            subtitleView.setText("本機分享已停止");
            shareRows.add(actionRow("重新啟動", "建立新的 PIN", () ->
                    startShareService(shareWifi)));
        } else if (url == null || pin == null) {
            if (++sharePollAttempts > 30) {
                subtitleView.setText("分享服務沒有回應");
                showRows(Arrays.asList(
                        actionRow("重試", "建立新的 PIN", () ->
                                startShareService(shareWifi)),
                        actionRow("停止分享", "", this::stopShareService),
                        actionRow("返回首頁", "", this::showHome)));
                return;
            }
            subtitleView.setText("正在建立一次性連線…");
            shareRows.add(actionRow("準備中", "請稍候", this::renderShareStatus));
            mainHandler.postDelayed(shareStatusPoll, 350L);
        } else {
            sharePollAttempts = 0;
            String readyKey = url + "\n" + pin;
            if (readyKey.equals(renderedShareKey)) {
                mainHandler.removeCallbacks(shareStatusPoll);
                mainHandler.postDelayed(shareStatusPoll, 5_000L);
                return;
            }
            renderedShareKey = readyKey;
            subtitleView.setText("PIN " + pin + "  •  " + url);
            if (shareWifi) {
                shareRows.add(actionRow("手機開啟", url,
                        () -> toast("請在手機瀏覽器輸入上方網址")));
                shareRows.add(headerRow("限個人熱點／可信 Wi‑Fi；HTTP 不會加密內容"));
            } else {
                shareRows.add(actionRow("電腦終端機", "adb forward tcp:8765 tcp:8765",
                        () -> toast("轉送後開啟 http://127.0.0.1:8765")));
                shareRows.add(headerRow("瀏覽器：http://127.0.0.1:8765"));
            }
            mainHandler.postDelayed(shareStatusPoll, 5_000L);
        }
        shareRows.add(actionRow("停止分享", "立即中斷並銷毀 PIN", this::stopShareService));
        shareRows.add(actionRow("返回首頁", "分享會繼續，10 分鐘閒置後自停", this::showHome));
        showRows(shareRows);
    }

    private void stopShareService() {
        shareRequested = false;
        renderedShareKey = null;
        mainHandler.removeCallbacks(shareStatusPoll);
        try {
            ShareService.stop(this);
        } catch (RuntimeException ignored) {
            // 服務若已由閒置計時器停止，結果相同。
        }
        unbindShareService();
        toast("本機分享已停止");
        showHome();
    }

    private void returnToMedia() {
        releasePreviewMedia();
        showMedia(currentFilter);
    }

    private void navigateBack() {
        if (scanCancelled != null) {
            scanCancelled.set(true);
            return;
        }
        if (screen == Screen.HOME) {
            finish();
        } else if (screen == Screen.PREVIEW) {
            releasePreviewMedia();
            showActions(activeItem);
        } else if (screen == Screen.ACTIONS) {
            returnToMedia();
        } else {
            showHome();
        }
    }

    private void activateSelected() {
        if (busy) {
            return;
        }
        if (screen == Screen.PREVIEW) {
            if (activeVideo != null) {
                if (activeVideo.isPlaying()) {
                    activeVideo.pause();
                } else {
                    activeVideo.start();
                }
            }
            return;
        }
        if (listView == null || rows.isEmpty()) {
            return;
        }
        int position = listView.getSelectedItemPosition();
        if (position < 0) {
            position = firstActionPosition();
        }
        if (position >= 0 && position < rows.size() && rows.get(position).action != null) {
            rows.get(position).action.run();
        }
    }

    private void stepSelection(int direction) {
        if (busy || screen == Screen.PREVIEW) {
            return;
        }
        if (listView == null || rows.isEmpty()) {
            return;
        }
        int position = listView.getSelectedItemPosition();
        if (position < 0) {
            position = direction > 0 ? -1 : rows.size();
        }
        for (int attempts = 0; attempts < rows.size(); attempts++) {
            position += direction;
            if (position < 0) {
                position = rows.size() - 1;
            } else if (position >= rows.size()) {
                position = 0;
            }
            if (rows.get(position).action != null) {
                listView.setSelection(position);
                listView.setItemChecked(position, true);
                return;
            }
        }
    }

    private int firstActionPosition() {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).action != null) {
                return index;
            }
        }
        return -1;
    }

    private void showRows(List<UiRow> newRows) {
        content.removeAllViews();
        rows.clear();
        rows.addAll(newRows);
        listView = new ListView(this);
        listView.setBackgroundColor(Color.BLACK);
        listView.setDividerHeight(dp(1));
        listView.setDivider(solid(COLOR_DIM, 0, 0));
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setSelector(solid(COLOR_FOCUS, COLOR_BORDER, dp(2)));
        listView.setFocusable(true);
        listView.setFocusableInTouchMode(true);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            UiRow row = rows.get(position);
            if (row.action != null) {
                row.action.run();
            }
        });
        content.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int first = firstActionPosition();
        if (first >= 0) {
            listView.setSelection(first);
            listView.setItemChecked(first, true);
            listView.requestFocus();
        }
    }

    private interface ThrowingTask {
        void run(TaskContext task) throws Exception;
    }

    private final class TaskContext {
        private final long generation;

        TaskContext(long generation) {
            this.generation = generation;
        }

        void post(Runnable action) {
            postIfTaskCurrent(generation, action);
        }
    }

    private void runTask(String message, ThrowingTask task) {
        cancelActiveTask();
        long generation = taskGeneration.incrementAndGet();
        TaskContext context = new TaskContext(generation);
        if (message != null) {
            showProgressOverlay();
            subtitleView.setText(message);
        }
        activeTask = ioExecutor.submit(() -> {
            try {
                task.run(context);
            } catch (Exception failure) {
                context.post(() -> {
                    hideProgressOverlay();
                    toast("處理失敗：" + safeMessage(failure));
                });
            } finally {
                context.post(() -> {
                    activeTask = null;
                    hideProgressOverlay();
                });
            }
        });
    }

    private void postIfTaskCurrent(long generation, Runnable action) {
        mainHandler.post(() -> {
            if (taskGeneration.get() == generation) {
                action.run();
            }
        });
    }

    private void cancelActiveTask() {
        taskGeneration.incrementAndGet();
        if (scanCancelled != null) {
            scanCancelled.set(true);
            scanCancelled = null;
        }
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
        hideProgressOverlay();
    }

    private void releasePreviewMedia() {
        if (activeVideo != null) {
            activeVideo.stopPlayback();
            activeVideo = null;
        }
        if (activeBitmap != null && !activeBitmap.isRecycled()) {
            activeBitmap.recycle();
            activeBitmap = null;
        }
    }

    private void showProgressOverlay() {
        busy = true;
        if (listView != null) listView.setEnabled(false);
        if (progressBar == null) {
            progressBar = new ProgressBar(this);
            progressBar.setIndeterminate(true);
        }
        if (progressBar.getParent() == null) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(54), dp(54));
            params.gravity = Gravity.CENTER;
            content.addView(progressBar, params);
        }
    }

    private void hideProgressOverlay() {
        busy = false;
        if (listView != null) listView.setEnabled(true);
        if (progressBar != null && progressBar.getParent() == content) {
            content.removeView(progressBar);
        }
    }

    private UiRow actionRow(String title, String detail, Runnable action) {
        return new UiRow(title, detail, action, false);
    }

    private UiRow headerRow(String title) {
        return new UiRow(title, "", null, true);
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public UiRow getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position).action != null;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            UiRow row = getItem(position);
            LinearLayout holder = new LinearLayout(MainActivity.this);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setGravity(Gravity.CENTER_VERTICAL);
            holder.setPadding(dp(12), dp(row.header ? 5 : 10), dp(10), dp(row.header ? 5 : 10));
            holder.setMinimumHeight(dp(row.header ? 38 : 76));

            TextView primary = text(row.header ? 15 : 20,
                    row.header ? COLOR_DIM : COLOR_TEXT,
                    row.header ? Typeface.BOLD : Typeface.NORMAL);
            primary.setText(row.title);
            primary.setMaxLines(row.header ? 1 : 2);
            holder.addView(primary, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (!row.detail.isEmpty()) {
                TextView secondary = text(13, COLOR_DIM, Typeface.NORMAL);
                secondary.setText(row.detail);
                secondary.setMaxLines(2);
                holder.addView(secondary, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return holder;
        }
    }

    private static final class UiRow {
        final String title;
        final String detail;
        final Runnable action;
        final boolean header;

        UiRow(String title, String detail, Runnable action, boolean header) {
            this.title = title;
            this.detail = detail == null ? "" : detail;
            this.action = action;
            this.header = header;
        }
    }

    private TextView text(int sp, int color, int style) {
        TextView value = new TextView(this);
        value.setTextColor(color);
        value.setTextSize(sp);
        value.setTypeface(Typeface.create("sans", style));
        value.setIncludeFontPadding(false);
        return value;
    }

    private GradientDrawable solid(int color, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private static long startOfToday() {
        Calendar value = Calendar.getInstance();
        value.set(Calendar.HOUR_OF_DAY, 0);
        value.set(Calendar.MINUTE, 0);
        value.set(Calendar.SECOND, 0);
        value.set(Calendar.MILLISECOND, 0);
        return value.getTimeInMillis();
    }

    private static String dateGroup(long millis) {
        if (millis <= 0L) {
            return "時間未知";
        }
        long today = startOfToday();
        if (millis >= today) {
            return "今天";
        }
        if (millis >= today - 86_400_000L) {
            return "昨天";
        }
        return new SimpleDateFormat("yyyy 年 MM 月 dd 日", Locale.TAIWAN).format(millis);
    }

    private static String filterTitle(ViewFilter filter) {
        switch (filter) {
            case TODAY: return "今日拍攝";
            case PHOTOS: return "相片";
            case VIDEOS: return "影片";
            case FAVORITES: return "我的最愛";
            case PROTECTED: return "已保護";
            case LARGE: return "大檔案";
            case DUPLICATES: return "重複檔案";
            case TRASH: return "垃圾桶";
            case ALL:
            default: return "所有媒體";
        }
    }

    private static String ellipsize(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }
}
