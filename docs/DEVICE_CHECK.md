# Rokid Glasses 真機驗收清單

繁體中文｜[English](DEVICE_CHECK.en.md)｜[日本語](DEVICE_CHECK.ja.md)

消費版 RV101／RV102 的公開 Android 規格不完整。第一次安裝前後，請用具資料功能的除錯線確認以下項目。

## 1. 裝置與系統

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
adb shell wm size
adb shell wm density
adb shell getprop sys.usb.config
adb shell dumpsys usb
```

預期社群資料為 Android 12／API 32、`arm64-v8a`、顯示 480×640；若不同，請保留完整輸出再調整建置設定。

## 2. 檔案權限、MediaStore 與媒體路徑

```bash
adb shell ls -la /sdcard/DCIM
adb shell ls -la /sdcard/DCIM/Camera
adb shell ls -la /sdcard/DCIM/album
adb shell ls -la /sdcard/Pictures
adb shell ls -la /sdcard/Movies
adb shell content query --uri content://media/external/images/media --projection _id:_display_name:relative_path:owner_package_name
adb shell content query --uri content://media/external/video/media --projection _id:_display_name:relative_path:owner_package_name
```

判定：

- 相機檔案位於共享儲存：安裝單一版本，並在 App 內允許「所有檔案存取」。
- 未完成特殊權限時只應進入 MediaStore 唯讀備援，不得假裝異動成功。
- 相機檔案只在原廠 App 私人目錄：普通第三方 APK 無法安全存取，需向 Rokid 取得官方媒體 API／簽章權限。

## 3. 觸控板事件

先列出輸入裝置，再於 App 前景執行即時事件觀察（按 `Ctrl+C` 結束）：

```bash
adb shell getevent -pl
adb shell getevent -lt
```

逐一測試向前／向後滑、點按、雙擊、長按、返回及 DPAD，並同時確認 App 的選取、開啟與返回反應。若正式版韌體禁止 shell 讀取 `/dev/input`，改執行 `adb shell dumpsys input` 保存裝置／按鍵配置，再以畫面反應逐項記錄結果。App 支援標準 DPAD、Generic Motion 與 Touch MotionEvent；OEM 私有事件若不同，需依實機輸出補映射。

## 4. 分享模式

USB：

```bash
adb forward --no-rebind tcp:8765 tcp:8765
curl --fail --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:8765/
adb forward --remove tcp:8765
```

上述 GET 應輸出 `200`。

Wi‑Fi：只在自己的手機熱點測試。確認 App 顯示的 IP 是 RFC1918 私有位址、停止分享後連線埠立即關閉，且 10 分鐘無操作會自動停止。

## 5. 破壞性操作

只用測試檔驗證：重新命名、移到垃圾桶、還原、上傳中斷、重複檔名、空間不足及 2GB 以上影片。確認任何失敗都不留下已發布的半檔案；App 不提供永久刪除。
