# Security Notes

[繁體中文](SECURITY.md) | English | [日本語](SECURITY.ja.md)

## Security boundaries

- The service is off by default and can be started only by the user from the foreground interface on the glasses.
- USB mode listens only on the device loopback interface. Wi-Fi mode listens only on the current private IPv4 address.
- Each start generates a new pairing code and a high-entropy session token. Failed attempts are rate-limited, and the service stops automatically after 10 minutes of inactivity.
- The web API uses opaque server-side IDs and does not accept client-provided file paths. Direct-file access validates canonical paths and symbolic links; the MediaStore fallback validates the primary volume, relative-path allowlist, and actual readability.
- Mutating operations require a same-origin session cookie and custom CSRF header. Responses use CSP, `frame-ancestors 'none'`, `nosniff`, and `no-store`.
- An upload is fully written to a temporary file and `fsync`ed before it is moved to its final name, without replacing an existing same-name file. Delete actions move items to trash by default.
- The app does not request camera, microphone, location, contacts, advertising ID, or launch-at-boot permission, and it does not connect to any external host.
- QR codes are encoded entirely on the glasses without an external QR service. The screen wake lock is held only during sharing and is released when sharing stops or times out.

## HTTP limitation

When a browser connects directly to a local-network IP address, it is not possible to provide all three of the following simultaneously: fully offline operation, no certificate warning, and validation by a public certificate authority. Wi-Fi mode in this version therefore uses HTTP. The PIN and cookie authenticate operation privileges, but **do not provide transport confidentiality or defend against an on-path attacker on the same network**.

Recommended order:

1. Use USB with ADB forwarding for sensitive files.
2. Use Wi-Fi only on your own phone hotspot, after confirming that no other client is connected.
3. Do not start sharing on a public or shared enterprise network.
4. Stop the service immediately when finished.

A future phone controller that stores no user data could generate a device certificate with Android Keystore and use certificate pinning on the phone, providing end-to-end encryption without a warning screen.

## Out of scope

- Glasses that are rooted, controlled by a malicious system app, or running replaced firmware.
- An ADB host that the user has already trusted.
- Copies and browser caches on the phone or computer after an explicit user download.
- Data-processing behavior of Rokid's stock camera or Hi Rokid itself.

## Reporting a problem

Do not post real photos, videos, pairing codes, IP addresses, serial numbers, or complete logs in a public issue. Include only the app version, YodaOS version, Android API level, reproduction steps, and a de-identified error description.
