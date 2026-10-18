# Battery Monitor — installation and direct updates

## Single app, single release
The permanent Android application ID is **`com.tihloh.bmsmonitor`**. Do not add an `.debug` suffix or publish a second release channel. The repository's only APK publishing workflow is **Build and Release Battery Monitor** (`.github/workflows/android-apk.yml`).

Every update committed to `main` starts that workflow. Once signing credentials are configured, a successful build publishes exactly one signed `Battery-Monitor-vX.Y.Z.apk` to GitHub Releases with increasing `versionCode`. Pull requests run tests without distributing APKs.

**Never publish a temporary GitHub Actions debug-signed APK as an update.** Builds must fail rather than create an APK that could produce `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Historical `*-dev.apk`/`*-debug.apk` prereleases are obsolete and must not be treated as updatable releases.

## Direct updates in the app
- Updates & About checks stable versioned GitHub Releases.
- If a newer compatible version is available, the app downloads it into private cache (not Downloads).
- Before installing, it validates the APK archive, package ID, version code and, when available, the installed and downloaded signing certificates.
- Android's PackageInstaller requests the installation approval required by the OS.
- The app deletes the temporary APK after preparing the install, including on failure. Old installer sessions are cleaned up.
- Background checks can notify the user, subject to their notification preference and Android permission.

## Required stable signing identity
Android only installs an update over the existing app if both APKs have the same package name and compatible signing certificates. **Matching package IDs alone is insufficient**. GitHub cannot reconstruct the private signing key from an existing APK.

Configure these GitHub Actions repository secrets once:
- `ANDROID_KEYSTORE_BASE64`: base64 of your private keystore (do not commit it)
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Back up the original keystore securely and reuse it for every release. If an old installed APK was built with a different ephemeral debug key and that key is unavailable, Android requires a one-time migration/reinstallation. No safe in-app code can bypass signature verification. Back up any valuable app-local data before uninstalling.

## Bluetooth discovery and telemetry
- Allow Nearby Devices on Android 12+ and precise Location when scanning requires it; keep the Bluetooth and device Location switches on when necessary.
- Close other BLE clients before connecting. Scan nearby devices or enter a BLE MAC address.
- The monitor supports read-only Gentai/ECO, supported JBD/Xiaoxiang FF00 and standard Bluetooth Battery Service profiles. Some JBD-branded devices use other firmware/protocols and may require additional drivers.
- The diagnostic log shows GATT services, packet status and compatibility failures. The app is **not** a battery protection system.
