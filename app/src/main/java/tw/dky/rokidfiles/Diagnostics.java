package tw.dky.rokidfiles;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * 程式內診斷環形緩衝：只保留最近 N 筆事件，供「匯出診斷記錄」寫出。
 * 隱私規則：不得記錄完整檔案路徑、session token、PIN 或遠端位址；
 * 檔名一律先過 {@link #safeName}，數字欄位用於容量與計數等非識別資訊。
 * 所有方法皆可在任意執行緒呼叫；緩衝滿時覆蓋最舊事件。
 */
public final class Diagnostics {
    /** 足夠涵蓋一次典型操作流程，又遠低於一封郵件可容納的行數。 */
    private static final int MAX_EVENTS = 200;
    private static final int MAX_MESSAGE_CHARS = 160;
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>(MAX_EVENTS);
    private static final Object LOCK = new Object();

    private Diagnostics() {
    }

    public enum Level {
        INFO,
        WARN,
        ERROR
    }

    public static void info(String event) {
        record(Level.INFO, event);
    }

    public static void warn(String event) {
        record(Level.WARN, event);
    }

    public static void error(String event) {
        record(Level.ERROR, event);
    }

    private static void record(Level level, String rawEvent) {
        String event = truncate(sanitizeEvent(rawEvent));
        String line = String.format(Locale.US, "%s %s %s",
                Instant.now().toString(), level, event);
        synchronized (LOCK) {
            if (EVENTS.size() == MAX_EVENTS) {
                EVENTS.pollFirst();
            }
            EVENTS.addLast(line);
        }
    }

    /** 寫出緩衝內容到指定檔案；失敗時丟出 IOException 由呼叫端提示。 */
    public static void exportTo(File target) throws IOException {
        String snapshot;
        synchronized (LOCK) {
            snapshot = String.join("\n", EVENTS);
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("無法建立輸出目錄");
        }
        try (PrintWriter writer = new PrintWriter(target, "UTF-8")) {
            writer.println("GlassesFiles 診斷記錄 " + Instant.now());
            writer.println("格式：ISO 時間 層級 事件");
            writer.println(snapshot);
            if (writer.checkError()) {
                throw new IOException("寫入診斷記錄失敗");
            }
        }
    }

    /** 目前緩衝事件數（供 UI 顯示）。 */
    public static int size() {
        synchronized (LOCK) {
            return EVENTS.size();
        }
    }

    /** 去除控制字元、路徑與常見驗證值；package-private 供隱私回歸測試。 */
    static String sanitizeEvent(String message) {
        if (message == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char value = message.charAt(i);
            cleaned.append(Character.isISOControl(value) ? ' ' : value);
        }

        StringBuilder result = new StringBuilder(cleaned.length());
        for (String segment : cleaned.toString().trim().split("\\s+")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            String lower = segment.toLowerCase(Locale.ROOT);
            if (segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
                result.append("[路徑省略]");
            } else if (looksSensitive(lower)) {
                result.append("[敏感值省略]");
            } else {
                result.append(segment);
            }
        }
        return result.toString();
    }

    private static boolean looksSensitive(String lowerSegment) {
        return lowerSegment.startsWith("token=")
                || lowerSegment.startsWith("token:")
                || lowerSegment.startsWith("token：")
                || lowerSegment.startsWith("pin=")
                || lowerSegment.startsWith("pin:")
                || lowerSegment.startsWith("pin：")
                || lowerSegment.startsWith("cookie=")
                || lowerSegment.startsWith("cookie:")
                || lowerSegment.startsWith("cookie：")
                || lowerSegment.startsWith("rk_session=")
                || lowerSegment.startsWith("x-rokid-csrf=");
    }

    private static String truncate(String message) {
        return message.length() <= MAX_MESSAGE_CHARS
                ? message : message.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    /**
     * 把 Throwable 轉成安全的單行描述。若訊息含路徑分隔符號，視為可能夾帶路徑，
     * 只保留例外類別名稱。
     */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()
                || message.indexOf('/') >= 0 || message.indexOf('\\') >= 0) {
            return failure.getClass().getSimpleName();
        }
        return failure.getClass().getSimpleName() + ": " + sanitizeEvent(message);
    }

    /** 取路徑的最後一段做為安全檔名表示（不含目錄資訊）。 */
    public static String safeName(String pathOrName) {
        if (pathOrName == null || pathOrName.isEmpty()) {
            return "(null)";
        }
        int cut = Math.max(pathOrName.lastIndexOf('/'), pathOrName.lastIndexOf('\\'));
        return cut >= 0 ? pathOrName.substring(cut + 1) : pathOrName;
    }
}
