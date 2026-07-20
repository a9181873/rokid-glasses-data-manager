# Rokid Glasses On-Device Acceptance Checklist

[繁體中文](DEVICE_CHECK.md) | English | [日本語](DEVICE_CHECK.ja.md)

The public Android specifications for the consumer RV101/RV102 are incomplete. Before and after the first installation, use a debugging cable with data support to verify the following items.

## 1. Device and system

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

Community information suggests Android 12/API 32, `arm64-v8a`, and a 480×640 display. If your results differ, retain the complete output before adjusting the build configuration.

## 2. File permission, MediaStore, and media paths

```bash
adb shell ls -la /sdcard/DCIM
adb shell ls -la /sdcard/DCIM/Camera
adb shell ls -la /sdcard/DCIM/album
adb shell ls -la /sdcard/Pictures
adb shell ls -la /sdcard/Movies
adb shell content query --uri content://media/external/images/media --projection _id:_display_name:relative_path:owner_package_name
adb shell content query --uri content://media/external/video/media --projection _id:_display_name:relative_path:owner_package_name
```

Interpretation:

- Camera files are in shared storage: install the single edition and grant “All files access” in the app.
- Until the special permission has been granted, the app must use its read-only MediaStore fallback and must never report a file modification as successful.
- Camera files exist only in the stock app's private directory: an ordinary third-party APK cannot access them safely. An official Rokid media API or signing permission is required.

## 3. Touchpad events

List the input devices first, then observe live events with the app in the foreground (press `Ctrl+C` to stop):

```bash
adb shell getevent -pl
adb shell getevent -lt
```

Test forward/backward swipes, tap, double-tap, long-press, Back, and DPAD individually while confirming the corresponding selection, open, and back behavior in the app. If a production firmware denies shell access to `/dev/input`, run `adb shell dumpsys input` to save its device and key configuration, then record each gesture's visible app response. The app supports standard DPAD, Generic Motion, and Touch MotionEvent input; an OEM-private event requires a mapping based on the on-device evidence.

## 4. Sharing modes

USB:

```bash
adb forward --no-rebind tcp:8765 tcp:8765
curl --fail --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:8765/
adb forward --remove tcp:8765
```

The GET request above must print `200`.

Wi-Fi: test only on your own phone hotspot. Confirm that the IP shown by the app is an RFC1918 private address, that the port closes immediately after sharing stops, and that the service stops automatically after 10 minutes of inactivity.

## 5. Destructive operations

Use test files only to verify renaming, moving to trash, restoration, an interrupted upload, duplicate filenames, insufficient space, and videos larger than 2 GB. Confirm that no failure leaves a partially published file. The app provides no permanent-delete action.
