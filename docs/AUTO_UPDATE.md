# Android update and signing setup

This app follows the same general update approach as the user's POS app:
1. At launch, check public GitHub Release `experimental-latest` (throttled to 12 hours).
2. Compare the release's `versionName` in release notes with `BuildConfig.VERSION_NAME`.
3. Ask the user before downloading an updated APK.
4. Use Android PackageInstaller and the official system confirmation screen to finish installation. Android prevents silent installation for ordinary apps.

## Important — permanent signing key

GitHub's auto-generated debug signing key is ephemeral. Without stable signing, Android rejects updates over older APKs. This is a mandatory one-time configuration for reliable auto-updating.

Create one permanent keystore locally and store it securely. Do **not** commit the keystore or credentials to this public GitHub repo. Set these GitHub Actions secrets in repository Settings → Secrets and variables → Actions:

- `BMS_KEYSTORE_BASE64`: base64 encoding of the JKS/PKCS12 keystore
- `BMS_SIGNING_STORE_PASSWORD`
- `BMS_SIGNING_KEY_ALIAS`
- `BMS_SIGNING_KEY_PASSWORD`

With these configured, pushes to `main` build APKs signed with the same stable key. Always increase both `versionCode` and `versionName` in `app/build.gradle` on future releases.

**Transition from 0.1.0:** The original test APK was debug-signed by an ephemeral GitHub runner. Users will likely need to uninstall it once before installing the first stably signed APK, then later releases can update in place. Android will show a confirmation for updates and may require enabling "Install unknown apps."

## BLE test

- Close the Gentai app before opening BMS Monitor.
- Allow Nearby devices permission. On Android 11 or earlier, allow Location permission and switch on Location.
- Tap `Scan again`, select `ECO039E`, and wait for live readings.
- If not detected, move closer, use `Bluetooth log` → `Copy diagnostic log`, and share the message.
- No BMS configuration commands are included.

## Safety

Monitoring is experimental; charging and protection must remain the responsibility of the BMS. No automatic protective relay controls are implemented.
