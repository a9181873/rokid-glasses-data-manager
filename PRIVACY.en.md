# Privacy Notes

[繁體中文](PRIVACY.md) | English | [日本語](PRIVACY.ja.md)

Glasses File Station（眼鏡檔案站） uses an offline, local-first design.

## What the app does not do

- It does not create an account.
- It does not connect to the developer, Rokid, or any third-party cloud service.
- It includes no advertising, analytics, crash-reporting, push-notification, social, or tracking SDK.
- It does not request Android location permission or read the current location, contacts, microphone, camera, call data, or advertising identifier.
- It does not automatically synchronize photos, videos, or filenames to a phone, computer, or the Internet.

## Data processed locally by the app

- Photo and video metadata, thumbnails, and streamed content from the user-authorized folders.
- Protection and favorite markers, trash restoration locations, the duplicate-file index, and required security-session state. This data remains local to the app and is not written back into the original media.

## Browser sharing

The app starts listening only after the user explicitly enables USB or Wi-Fi sharing from the foreground interface on the glasses. Each start uses a new pairing code and session token. They become invalid when sharing is stopped or times out.

When you preview or download through a browser, the selected media is transferred to that phone or computer. The app cannot control how the phone or browser subsequently stores, caches, or backs up the copy.

Wi-Fi mode currently uses HTTP on the local network and cannot prevent eavesdropping by another party on that network. Use USB mode for sensitive data. See [SECURITY.en.md](SECURITY.en.md) for full details.
