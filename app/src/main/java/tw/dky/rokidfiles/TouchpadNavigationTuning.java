package tw.dky.rokidfiles;

/** 純數值的觸控板調校規則，與 Android UI 分離，方便單元測試與真機微調。 */
final class TouchpadNavigationTuning {
    private static final float SCROLL_THRESHOLD = 1.0f;
    private static final int MAX_SCROLL_STEPS = 3;

    private TouchpadNavigationTuning() {
    }

    /** 沿用既有 Rokid 映射：負值往下一項，正值往上一項。 */
    static int directionForPrimary(float primary) {
        return primary < 0f ? 1 : -1;
    }

    /** 慢撥一格、一般甩動兩格、快速甩動四至八格，永不一次跳過八個項目。 */
    static int flingSteps(float velocityPixelsPerSecond) {
        float speed = Math.abs(velocityPixelsPerSecond);
        if (speed < 1_200f) {
            return 1;
        }
        if (speed < 2_400f) {
            return 2;
        }
        if (speed < 4_000f) {
            return 4;
        }
        return Math.min(8, 5 + (int) ((speed - 4_000f) / 2_000f));
    }

    /**
     * 部分 Rokid 韌體把鏡腳滑動送成許多小 ACTION_SCROLL；先累積到一個單位才移動，
     * 並把單次輸出限制為三格。方向反轉時丟棄舊方向餘量，避免回滑仍向前跳。
     */
    static final class ScrollAccumulator {
        private float remainder;

        int consume(float delta) {
            if (!Float.isFinite(delta) || delta == 0f) {
                return 0;
            }
            if (remainder != 0f && Math.signum(remainder) != Math.signum(delta)) {
                remainder = 0f;
            }
            remainder += delta;
            int whole = (int) Math.floor(Math.abs(remainder) / SCROLL_THRESHOLD);
            if (whole == 0) {
                return 0;
            }
            int direction = remainder > 0f ? 1 : -1;
            int emitted = Math.min(MAX_SCROLL_STEPS, whole);
            // 大量雜訊不排隊到下一事件；只保留不足一格的小數餘量。
            remainder = direction * (Math.abs(remainder) - whole * SCROLL_THRESHOLD);
            return direction * emitted;
        }

        void reset() {
            remainder = 0f;
        }
    }
}
