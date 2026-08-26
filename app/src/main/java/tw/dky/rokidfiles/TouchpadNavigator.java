package tw.dky.rokidfiles;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;

import java.util.Objects;

/**
 * Rokid 鏡腳觸控板事件轉譯器。手勢語義維持既有設定：單擊開啟、雙擊返回、長按動作；
 * 只把滑動速度與零碎 ACTION_SCROLL 正規化成「方向＋格數」。
 */
final class TouchpadNavigator {
    interface Callbacks {
        void onSingleTap();

        void onDoubleTap();

        void onLongPress();

        /** direction: -1 上一項，+1 下一項；steps 永遠 >= 1。 */
        void onNavigate(int direction, int steps);
    }

    private final GestureDetector detector;
    private final Callbacks callbacks;
    private final TouchpadNavigationTuning.ScrollAccumulator scrollAccumulator =
            new TouchpadNavigationTuning.ScrollAccumulator();

    TouchpadNavigator(Context context, Callbacks callbacks) {
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        detector = new GestureDetector(
                Objects.requireNonNull(context, "context"),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent event) {
                        scrollAccumulator.reset();
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent event) {
                        TouchpadNavigator.this.callbacks.onSingleTap();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent event) {
                        TouchpadNavigator.this.callbacks.onDoubleTap();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent event) {
                        TouchpadNavigator.this.callbacks.onLongPress();
                    }

                    @Override
                    public boolean onFling(
                            MotionEvent first,
                            MotionEvent second,
                            float velocityX,
                            float velocityY) {
                        float primary = Math.abs(velocityX) >= Math.abs(velocityY)
                                ? velocityX : -velocityY;
                        int direction = TouchpadNavigationTuning.directionForPrimary(primary);
                        TouchpadNavigator.this.callbacks.onNavigate(
                                direction,
                                TouchpadNavigationTuning.flingSteps(primary));
                        return true;
                    }
                });
    }

    boolean onTouchEvent(MotionEvent event) {
        return detector.onTouchEvent(event);
    }

    boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_SCROLL) {
            return false;
        }
        float horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
        float vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        float primary = Math.abs(horizontal) > Math.abs(vertical) ? horizontal : vertical;
        int units = scrollAccumulator.consume(primary);
        if (units == 0) {
            return primary != 0f; // 已累積小量事件，避免同一事件再被 ListView 消費。
        }
        callbacks.onNavigate(
                TouchpadNavigationTuning.directionForPrimary(units), Math.abs(units));
        return true;
    }
}
