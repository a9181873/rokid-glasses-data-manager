package tw.dky.rokidfiles.share;

/**
 * 接收已完成 HTTP session、同源與 CSRF 驗證的眼鏡遙控指令。
 * ShareService 會在 Android 主執行緒呼叫；Activity 應在 onStart 註冊、onStop 解除註冊。
 */
public interface RemoteCommandListener {
    enum Command {
        PREVIOUS,
        NEXT,
        OPEN,
        BACK
    }

    /** 已處理回傳 true；目前畫面不接受該動作時回傳 false。 */
    boolean onRemoteCommand(Command command);
}
