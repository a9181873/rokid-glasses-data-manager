# Rokid Glasses File Manager App（Rokid眼鏡檔案管理APP） User Guide

[繁體中文](USER_GUIDE.md) | English | [日本語](USER_GUIDE.ja.md)

## Three things to know first

1. **Your phone needs no separate file-management app.** Safari, Chrome, or another modern browser is sufficient.
2. **The gallery is not synchronized in advance.** Lists and thumbnails are retrieved on demand. A copy is created on the phone or computer only when you select Download.
3. **Use USB for sensitive files whenever possible.** Use Wi-Fi mode only with your own phone hotspot or another trusted network.

## Interface language and actual labels

The current APK and browser interface are in Traditional Chinese. The English terms in this guide explain their meaning; use these actual labels on screen:

| Meaning | Label shown in the app |
|---|---|
| App launcher | `Rokid眼鏡檔案管理APP` |
| Allow file management | `允許檔案管理` |
| USB computer management | `USB 電腦管理` |
| Wi-Fi phone management | `Wi‑Fi 手機管理` |
| Stop sharing | `停止分享` |
| Protect / remove protection | `設為保護` / `解除保護` |
| Move to trash / restore | `移到垃圾桶` / `還原` |

## First installation

### Method A: Hi Rokid Toolbox

1. Update Hi Rokid and YodaOS-Sprite on the glasses.
2. Open Toolbox in Hi Rokid.
3. Select the delivered `GlassesFiles.apk` and install it on the glasses.
4. Put on the glasses and look for `Rokid眼鏡檔案管理APP` near the end of the app list.

The Hi Rokid interface can vary slightly by market. If Toolbox is unavailable, use Method B.

### Method B: ADB debugging cable

1. Obtain a Rokid debugging cable with data contacts. The charging cable included in the box might not carry data.
2. Enable developer mode and ADB debugging in Hi Rokid or the settings on the glasses.
3. Connect the glasses to a computer and confirm the connection:

```bash
adb devices -l
```

4. Install the APK:

```bash
adb install -r GlassesFiles.apk
```

## Initial permission

1. Open `Rokid眼鏡檔案管理APP`.
2. Select `允許檔案管理` (Allow file management).
3. On the Android settings screen, allow “All files access.”
4. Return to the app. The permission is retained and does not need to be granted on every launch.

Although the app receives file-manager permission, its implementation manages only allowlisted media locations such as `DCIM/Camera`, `DCIM/album`, `Pictures`, and `Movies`. It does not browse system files or private data belonging to other apps.

## Controls on the glasses

| Action | Function |
|---|---|
| Swipe forward/backward | Previous/next item |
| Tap | Open the current item or activate a button |
| Double-tap | Go back one level |
| Long-press | Open item actions (if the firmware reserves this gesture for AI, tap through to the action page instead) |
| Physical Back/DPAD | Standard Android Back and directional-key behavior |

The home screen provides:

- Captured today
- All photos and videos
- Photos only/videos only
- Protected
- Favorites
- Large files
- Duplicate files
- Trash
- USB computer management
- Wi-Fi phone management
- Storage permission and capacity information

### Protect important files

Select “Protect” on a file's action page. A protected item cannot be renamed or moved to trash until you manually remove its protection. The marker is stored only in the app's private settings on the glasses and does not modify the original media.

### Delete and restore

- The normal “Delete” action only moves an item to the recoverable trash.
- An item in the trash can be restored to its original location. If its name is already in use, the app stops and asks you to resolve the conflict first.

## Phone-browser management and remote control

1. Turn on your phone's personal hotspot.
2. Connect the glasses to that hotspot.
3. Select “Wi-Fi phone management” on the home screen of the glasses.
4. Scan the offline QR code shown on the glasses with the phone camera. If the camera cannot recognize it, enter an address such as `http://192.168.43.20:8765` manually.
5. Enter the current six-digit pairing code.

The glasses display stays awake while sharing and phone control are active so that remote actions remain visible. Normal sleep behavior resumes when sharing stops or after the 10-minute inactivity timeout. The QR code is generated locally on the glasses; its URL is never sent to an external service.

After pairing, you can:

- Filter by today, a specific date, photo, video, protected, favorite, large file, or duplicate group.
- Stream a preview directly. Video supports Range requests, so seeking or reconnecting does not require transferring the entire video again.
- Toggle protection or favorite status, rename, move to trash, or download one item at a time.
- Control the glasses interface with the four large Previous, Next, Open, and Back buttons under “Glasses remote control.”

When finished, select “Stop sharing” on the glasses. The service also stops automatically after 10 minutes without activity.

## USB computer management

1. Select “USB computer management” on the home screen of the glasses.
2. Connect the debugging cable and run:

```bash
adb forward --no-rebind tcp:8765 tcp:8765
```

3. Open `http://127.0.0.1:8765` in the computer browser.
4. Enter the pairing code shown on the glasses.
5. When finished, stop sharing and remove the forwarding rule:

```bash
adb forward --remove tcp:8765
```

## Organize duplicate and large files

- Large files are sorted directly by size, without reading their complete contents first.
- Duplicate scanning first groups files by size, then calculates SHA-256 through a small fixed streaming buffer. It does not load a video into RAM.
- By default, scanning runs only while charging. You can still start it manually when unplugged, but the glasses may drain their battery or warm up more quickly.
- A scan can be cancelled and cancellation does not modify any original file.
- You decide which copy to keep in each matching-hash group. The app never deletes duplicates automatically.

## Troubleshooting

### The phone cannot connect to the address

- Confirm that the phone and glasses are on the same private hotspot or Wi-Fi network.
- Use the IP address currently shown on the glasses, not an old bookmark.
- Temporarily disable a phone VPN or security tool that blocks local-network access, then try again.
- Stop and restart sharing. This also creates a new pairing code.

### Files are visible but cannot be renamed or deleted

The app is using its read-only MediaStore fallback. Return to the home screen, select “Allow file management,” and confirm that “All files access” is enabled in Android settings.

### A newly captured photo is missing

Return to the home screen and refresh. If the photo is still missing, follow the [on-device acceptance checklist](DEVICE_CHECK.en.md) to verify the actual storage path and MediaStore records used by the stock camera.

### A video does not play in the browser

The browser probably does not support the video's codec; this does not mean the downloaded file is damaged. Download the original and open it in a player that supports the codec.

### The system stopped the app

A low battery, folding the glasses to sleep, or a YodaOS power-saving policy may stop the foreground service. Put the glasses back on and start sharing manually again. The app does not configure itself to launch at boot merely to stay resident.
