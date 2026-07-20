package tw.dky.rokidfiles;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public final class UiFormat {
    private UiFormat() {
    }

    public static String bytes(long value) {
        if (value < 0L) {
            return "大小未知";
        }
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        double current = value;
        int unit = 0;
        while (current >= 1024d && unit < units.length - 1) {
            current /= 1024d;
            unit++;
        }
        if (unit == 0) {
            return value + " " + units[unit];
        }
        return String.format(Locale.TAIWAN, "%.1f %s", current, units[unit]);
    }

    public static String dateTime(long epochMillis) {
        if (epochMillis <= 0L) {
            return "時間未知";
        }
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.TAIWAN)
                .format(new Date(epochMillis));
    }

    public static String durationMinutes(long millis) {
        if (millis <= 0L) {
            return "未知";
        }
        long minutes = Math.max(1L, millis / 60_000L);
        long hours = minutes / 60L;
        long remainder = minutes % 60L;
        return hours > 0L ? hours + " 小時 " + remainder + " 分" : minutes + " 分";
    }
}
