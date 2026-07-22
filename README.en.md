# Rokid Glasses File Manager App（Rokid眼鏡檔案管理APP）

[繁體中文](README.md) | English | [日本語](README.ja.md)

[![Android CI](https://github.com/a9181873/rokid-glasses-data-manager/actions/workflows/android.yml/badge.svg)](https://github.com/a9181873/rokid-glasses-data-manager/actions/workflows/android.yml)

## Download the APK

Regular users do not need the Android SDK or a local build. Download the app that can be installed directly on the glasses:

**[Download GlassesFiles.apk](https://github.com/a9181873/rokid-glasses-data-manager/raw/refs/heads/main/dist/GlassesFiles.apk)**

After downloading, install it with Hi Rokid's Toolbox. If Toolbox is unavailable, use the ADB method below.

Designed for the consumer **Rokid Glasses RV101/RV102** with a green monochrome display. The app runs directly on the glasses and lets you manage the photos and videos stored on them from the glasses, a phone browser, or a computer. There is no need to synchronize the entire gallery to a phone first, and the app has no cloud service, account, advertising, or analytics.

> The installed APK and current interface use Traditional Chinese. Rokid does not publish every Android behavior of the consumer YodaOS as a stable contract. Before regular use, follow the [on-device acceptance checklist](docs/DEVICE_CHECK.en.md) to verify file paths, storage permissions, and touchpad events on your firmware.

## Features

- A 480×640 high-contrast black interface for the glasses: swipe to select, tap to activate, and double-tap to go back.
- Browse by photo, video, or date. Sampled thumbnails avoid loading a full 12 MP image into memory.
- List, preview, Range-stream, and explicitly download files from a phone or computer browser without synchronizing the originals first.
- Rename, move to trash, restore, and upload files. The app deliberately provides no permanent-delete action, reducing the risk of an unrecoverable touchpad mistake.
- USB mode listens only on the glasses' local `127.0.0.1:8765` interface and is used with ADB port forwarding.
- Wi-Fi mode listens only on the current private IPv4 address. Each start creates a new pairing code and 256-bit session token, and the service stops automatically after 10 minutes of inactivity.
- The glasses generate the Wi-Fi URL QR code entirely offline. Their display stays awake during phone management and returns to normal sleep behavior when sharing stops or times out.
- The MediaStore fallback rejects stale rows that cannot be opened and deduplicates by the real relative path, preventing one photo from appearing twice.
- The web interface is embedded entirely in the APK. It loads no CDN, font, tracking script, or external API.

## Storage permission

On first launch, you must explicitly grant Android's “All files access” permission. This lets a phone browser rename, move, and restore media without requiring confirmation on the glasses for every file. The implementation remains hard-limited to an allowlist of media locations such as `DCIM/Camera`, `DCIM/album`, `Pictures`, and `Movies`, and rejects symbolic links and canonical-path escapes. Without this permission, the app provides a read-only MediaStore fallback.

## Confirmed device characteristics

Rokid's currently published specifications for Rokid Glasses list binocular 480×640 green monochrome Micro-LED displays, a 30° field of view, up to 1,500 nits, Snapdragon AR1 Gen 1, 2 GB RAM, 32 GB storage, Wi-Fi 6, Bluetooth 5.3, and a 12 MP Sony IMX681. Available controls include the temple touchpad, physical buttons, voice, and Hi Rokid.

- [Official Rokid product specifications](https://global.rokid.com/products/rokid-glasses)
- [Rokid Academy controls and specifications](https://global.rokid.com/en-jp/pages/academy)
- [Rokid Security Center (RV101/RV102)](https://global.rokid.com/en-jp/pages/security-center)
- [Rokid Taiwan RV101 product page](https://www.rokid.com.tw/zh-TW/products/rokid-glasses)

Rokid publicly identifies the operating system only as **YodaOS-Sprite**. Details such as Android 12/API 32, `arm64-v8a`, and low-RAM configuration currently come from [community firmware research](https://github.com/buildwithfenna/rokid-docs). The app therefore does not depend on Google Play Services or a private Rokid SDK and supports Android API level 28 or later.

## Build from source (developers only)

Regular users should use the APK above and do not need an SDK. To modify the source code, follow these steps:

1. [Download and install Android Studio from the official Android website](https://developer.android.com/studio). Its setup wizard installs the Android SDK.
2. Open **Tools → SDK Manager** and install **Android SDK Platform 35**, **Android SDK Build-Tools**, and **Android SDK Platform-Tools**.
3. Open this project directory in Android Studio and wait for Gradle Sync to finish.
4. Select **Build → Build App Bundle(s) or APK(s) → Build APK(s)**.
5. Find the result at `app/build/outputs/apk/debug/app-debug.apk`.

For a command-line-only setup, get the Android SDK Command-Line Tools from the [official download page](https://developer.android.com/studio#command-tools) and install JDK 17. Then run these commands from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat test
```

On macOS/Linux, replace `gradlew.bat` with `./gradlew`.

The repository's release-signed APK is located at:

```text
dist/GlassesFiles.apk
```
SHA-256: `871c069afc572f0d68d8dc4b087b48ce5252aa51c3183fbf175c23e6d1973b58`. Signing certificate fingerprint: `b1052559eb22898762d7867b0d799d631e9743f89b4e69f6b9efc8a29972b729`.

The maintainer's local `private-signing/` directory contains the private key required for future in-place updates. It is excluded by `.gitignore` and is never pushed to GitHub. Keep an offline backup and never publish it.

## Install

Hi Rokid's Toolbox can install a local APK and remotely control the glasses. Apply available system updates to Hi Rokid and YodaOS-Sprite before proceeding. If Toolbox is unavailable, obtain a Rokid debugging cable with data contacts and enable ADB:

If the glasses already have an APK signed with another key, uninstall it before installing this file. Uninstalling clears that app's local data.

```bash
adb devices -l
adb install -r dist/GlassesFiles.apk
```

The magnetic charging cable included in the box might not carry data. After installation, look for `Rokid眼鏡檔案管理APP`, usually near the end of the app list on the glasses; the exact behavior depends on the device firmware.

## Use

### USB computer management (recommended)

1. On the glasses, open `Rokid眼鏡檔案管理APP` → `USB 電腦管理` (USB computer management).
2. Connect the debugging cable to the computer and run:

```bash
adb forward --no-rebind tcp:8765 tcp:8765
```

3. On the computer, open `http://127.0.0.1:8765` and enter the one-time pairing code shown on the glasses.
4. When finished, stop sharing on the glasses and run:

```bash
adb forward --remove tcp:8765
```

### Direct Wi-Fi management from a phone

1. Connect the glasses to your own phone hotspot, or connect the phone and glasses to the same trusted Wi-Fi network.
2. On the glasses, open `Wi‑Fi 手機管理` (Wi-Fi phone management).
3. Scan the offline QR code shown on the glasses with the phone camera (or enter `http://private-IP:8765` manually), then enter the current pairing code.
4. Stop sharing immediately when finished.

The Wi-Fi web interface retrieves only the list, thumbnail, or file that you request. It creates a copy on the phone or computer only when you select “Download.”

## Important limitations

- The Wi-Fi browser interface currently uses HTTP on the local network. The pairing code blocks unauthorized operations but **does not encrypt transferred content**. Do not use it on shared workplace, hotel, café, or other public networks. Use USB mode for sensitive content.
- Without “All files access,” the MediaStore fallback may allow browsing only. Return to the app's permission entry point and grant access before renaming, moving, or restoring files directly from a browser.
- An ordinary third-party app cannot force an OEM device to enable MTP or access another app's private `/data/user/0` directory.
- The green monochrome display is suitable for identifying filenames, dates, and composition, but not for judging original colors. Review full photos and videos in the phone or computer browser.

See [SECURITY.en.md](SECURITY.en.md) for the security design and known risks, and [PRIVACY.en.md](PRIVACY.en.md) for data handling.
