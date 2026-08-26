# Signed release process

`Signed Android Release` builds, signs, verifies, and publishes `GlassesFiles.apk` plus its SHA-256 file. It runs on a `v*` tag or manual dispatch.

## Required repository secrets

All four values are mandatory. The workflow fails before building if one is absent; it never publishes an unsigned APK.

- `ANDROID_KEYSTORE_BASE64`: base64 of the existing update keystore
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: alias of the existing signing key
- `ANDROID_KEY_PASSWORD`: private-key password

The key must match the certificate already used by installed releases. Losing or replacing it prevents in-place updates. Keep an offline backup; never commit the keystore or passwords.

## Release checks

Before publication the workflow runs unit tests, Android lint, and the release build. Because the app's minimum Android version is API 28, it applies APK Signature Scheme v3, runs `apksigner verify --verbose --print-certs`, and creates the checksum file.

The workflow decodes the key only into the runner's temporary directory and deletes it in an `always()` cleanup step. GitHub-hosted runners are ephemeral.

Until the four secrets are configured and the first GitHub Release is verified, retain `dist/GlassesFiles.apk` so the documented download link remains valid. After that migration, point all three README download links to the Release asset before removing the tracked APK.
