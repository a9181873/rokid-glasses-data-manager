# Rokid眼鏡檔案管理APP

繁體中文｜[English](README.en.md)｜[日本語](README.ja.md)

[![Android CI](https://github.com/a9181873/rokid-glasses-data-manager/actions/workflows/android.yml/badge.svg)](https://github.com/a9181873/rokid-glasses-data-manager/actions/workflows/android.yml)

## 直接下載 APK

一般使用者不需要 Android SDK，也不需要自行建置。請直接下載可安裝到眼鏡的 App：

**[下載 GlassesFiles.apk](https://github.com/a9181873/rokid-glasses-data-manager/raw/refs/heads/main/dist/GlassesFiles.apk)**

下載後可使用 Hi Rokid 的 Toolbox 安裝；若 Toolbox 無法使用，請參考下方的 ADB 安裝方式。

專為綠色單色顯示的消費版 **Rokid Glasses RV101／RV102** 設計。App 直接在眼鏡上執行，讓眼鏡、手機瀏覽器或電腦管理眼鏡內的相片與影片；不需先把整個相簿同步到手機，也沒有雲端、帳號、廣告或分析服務。

> App 安裝後的名稱固定為「Rokid眼鏡檔案管理APP」。Rokid 並未把消費版 YodaOS 的所有 Android 行為列為公開契約；正式使用前必須依 [真機驗收清單](docs/DEVICE_CHECK.md) 測試檔案路徑、儲存授權與觸控板事件。

## 功能

- 眼鏡端 480×640 黑底高對比介面：滑動選擇、點按執行、雙擊返回。
- 依相片／影片／日期瀏覽，採樣縮圖，不把 12MP 原圖整張載入記憶體。
- 手機或電腦瀏覽器直接列檔、預覽、Range 串流與明確下載，不預先同步原檔。
- 重新命名、移至垃圾桶、還原及上傳；App 不提供永久刪除，避免誤觸造成不可復原。
- USB 模式只監聽眼鏡本機 `127.0.0.1:8765`，搭配 ADB port forwarding。
- Wi‑Fi 模式只監聽當下的私有 IPv4；每次啟動重建配對碼與 256-bit 工作階段權杖，10 分鐘無操作自動停止。
- 網頁完全內嵌 APK，不載入 CDN、字型、追蹤碼或外部 API。

## 儲存權限

首次啟動需明確允許 Android 的「所有檔案存取」；這讓手機瀏覽器能在不逐檔回到眼鏡確認的情況下重新命名、移動及復原媒體。程式內仍硬性限制在 `DCIM/Camera`、`DCIM/album`、`Pictures`、`Movies` 等媒體白名單，並拒絕符號連結與 canonical path 逃逸；未授權時只提供 MediaStore 唯讀備援。

## 已確認的裝置條件

目前官方資料列出的 Rokid Glasses 規格是：雙眼 480×640 綠色單色 Micro‑LED、30° FOV、最高 1,500 nits、Snapdragon AR1 Gen 1、2GB RAM、32GB 儲存、Wi‑Fi 6、Bluetooth 5.3 與 12MP Sony IMX681。操作來源包含鏡腳觸控板、實體按鍵、語音及 Hi Rokid。

- [Rokid 官方產品規格](https://global.rokid.com/products/rokid-glasses)
- [Rokid Academy 操作與規格](https://global.rokid.com/en-jp/pages/academy)
- [Rokid Security Center（RV101／RV102）](https://global.rokid.com/en-jp/pages/security-center)
- [台灣 Rokid RV101 產品頁](https://www.rokid.com.tw/zh-TW/products/rokid-glasses)

官方只公開作業系統名稱 **YodaOS-Sprite**。Android 12／API 32、arm64-v8a 與 low-RAM 等細節目前來自[社群韌體研究](https://github.com/buildwithfenna/rokid-docs)，因此 App 不依賴 Google Play Services 或 Rokid 私有 SDK，並支援 API 28 以上的 Android 環境。

## 自行建置（僅供開發者）

一般使用者請直接使用上方的 APK，不需要安裝 SDK。若要修改程式碼，請依下列步驟建置：

1. 從 [Android 官方網站下載並安裝 Android Studio](https://developer.android.com/studio)。安裝精靈會一併設定 Android SDK。
2. 開啟 **Tools → SDK Manager**，安裝 **Android SDK Platform 35**、**Android SDK Build-Tools** 與 **Android SDK Platform-Tools**。
3. 用 Android Studio 開啟本專案資料夾，等待 Gradle Sync 完成。
4. 選擇 **Build → Build App Bundle(s) or APK(s) → Build APK(s)**。
5. 到 `app/build/outputs/apk/debug/app-debug.apk` 取得建置結果。

命令列使用者可從[官方下載頁](https://developer.android.com/studio#command-tools)取得 Android SDK Command-Line Tools，並準備 JDK 17。完成環境設定後，在專案根目錄執行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat test
```

macOS／Linux 請將 `gradlew.bat` 改為 `./gradlew`。

儲存庫附帶的正式簽署 APK 位於：

```text
dist/GlassesFiles.apk
```
SHA-256：`f73678a7035bbee60b5b9ee059ed08f9f1941d08a2c308879352fd851ee72a1c`。簽署憑證指紋：`4474b01e8e0fd74d484775e223344ca7703c779719d6a634621cce0d782fd9d8`。

維護者本機的 `private-signing/` 是後續覆蓋更新必須使用的私密簽章金鑰；它已由 `.gitignore` 排除，不會推送到 GitHub。請離線備份且不要公開。

## 安裝

Hi Rokid 的 Toolbox 可安裝本機 APK，並以手機遙控眼鏡。操作前請先完成 Hi Rokid 與 YodaOS-Sprite 的系統更新。若 Toolbox 不可用，需另購具資料接點的 Rokid 除錯線並開啟 ADB：

若眼鏡曾安裝使用其他簽章的 APK，請先解除安裝再安裝此檔案；解除安裝會清除該 App 的本機資料。

```bash
adb devices -l
adb install -r dist/GlassesFiles.apk
```

盒內磁吸充電線不一定具有資料功能。安裝後，App 通常會出現在眼鏡 App 清單末端；這項行為仍以實機韌體為準。

## 使用

### USB 電腦管理（推薦）

1. 在眼鏡開啟「Rokid眼鏡檔案管理APP」→「USB 電腦管理」。
2. 以除錯線連接電腦後執行：

```bash
adb forward --no-rebind tcp:8765 tcp:8765
```

3. 電腦開啟 `http://127.0.0.1:8765`，輸入眼鏡顯示的一次性配對碼。
4. 完成後在眼鏡停止分享，並執行：

```bash
adb forward --remove tcp:8765
```

### 手機 Wi‑Fi 直接管理

1. 讓眼鏡連到自己的手機熱點，或讓手機與眼鏡位於同一個可信任 Wi‑Fi。
2. 在眼鏡開啟「Wi‑Fi 手機管理」。
3. 手機瀏覽器開啟眼鏡顯示的 `http://私有IP:8765`，輸入當次配對碼。
4. 完成後立即停止分享。

Wi‑Fi 網頁只會按需取得清單、縮圖或你正在看的檔案。只有按下「下載」時才會在手機／電腦建立副本。

## 重要限制

- Wi‑Fi 瀏覽器版目前是區網 HTTP。配對碼可阻止未授權操作，但**不會加密傳輸內容**；不要在公司、飯店、咖啡店等共用網路使用。高敏感內容請用 USB 模式。
- 若未授予「所有檔案存取」，MediaStore 備援可能只能瀏覽；返回 App 的權限入口完成授權後，才可由瀏覽器直接重新命名、移動與復原。
- 一般第三方 App 無法替 OEM 強制開啟 MTP，也無法讀取其他 App 的私人 `/data/user/0` 目錄。
- 綠色單色顯示適合辨識檔名、日期與構圖，不適合判斷原始色彩；完整相片與影片建議在手機／電腦瀏覽器查看。

安全設計與已知風險請見 [SECURITY.md](SECURITY.md)，隱私處理請見 [PRIVACY.md](PRIVACY.md)。
