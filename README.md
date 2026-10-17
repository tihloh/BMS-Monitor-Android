# BMS Monitor Android

Native Android **experimental** Bluetooth LE monitor for Gentai/ECO-compatible 8S LiFePO4 BMS devices.

## Version 0.2.0

- Redesigned compact monitoring dashboard with clear connection status, SOC progress, live voltage, current, power, remaining capacity and available individual cell voltages.
- Scan without device-name filters, show all BLE devices, stronger scan settings, 18-second discovery, permission/location instructions and clearer timeout errors.
- Remember your selected BMS (ECO039E) to reconnect on later launches.
- Bluetooth diagnostic log hidden under **Diagnostics**, with a copy button.
- GitHub Releases update check on app launch (at most every 12 hours) and via **Updates**. New versions prompt to download and install using Android's PackageInstaller (requires Android confirmation).
- Source-based release signing uses the same four GitHub Actions keystore secrets approach as the owner's POS app.

## Use

1. Close the manufacturer's Gentai app to free its BLE connection.
2. Ensure Bluetooth and Android Location are enabled.
3. Install the APK and grant Nearby Devices and Location permissions.
4. Tap **Scan for BMS**, and choose `ECO039E` (or the device advertising the ECO BMS).
5. Check the live readings. If scanning or telemetry fails, open **Diagnostics → Copy Bluetooth log**.

The Android 12+ permissions are intentionally explicit to improve device discovery on phone models that require location for nearby BLE scanning.

## Known verified BLE profile

- Device advertised as `ECO039E` / `BMC1_039E`.
- Service `00000001-0000-1000-8000-00805f9b34fb`.
- Write: `00000002-0000-1000-8000-00805f9b34fb`.
- Notify: `00000003-0000-1000-8000-00805f9b34fb`.
- Read-only battery request: `AA21002100`.
- Experimental cell request: `AA22002200`.
- A captured fragmented response was decoded at 25.99 V, -1.15 A, 19% SOC, 19.48 Ah remaining and 100 Ah capacity, close to Gentai app observations.

**Do not use this experimental software for battery safety decisions or inverter/ATS cutoff controls.** No configuration commands are exposed.

## Installable Android APK updates

Like [tihloh/pos-source](https://github.com/tihloh/pos-source), BMS Monitor checks the latest **stable** GitHub Release, parses the version, downloads the APK, and requests Android installation permission when a newer release exists.

**Persistent APK signing is mandatory for in-place upgrades.** Android refuses updates whose signing certificate differs from the installed app. The GitHub Actions debug keystore normally changes between workflow runs and cannot be used to guarantee upgrades.

Configure these GitHub Actions repository secrets once (Settings → Secrets and variables → Actions) using a securely backed-up Android keystore; **never commit the keystore to the public repository**:

- `BMS_KEYSTORE_BASE64` (base64-encoded JKS/PKCS12 file contents)
- `BMS_KEYSTORE_PASSWORD`
- `BMS_KEY_ALIAS`
- `BMS_KEY_PASSWORD`

When all four are configured, main-branch builds use the stable signing key and publish an APK as a new versioned stable release `v0.2.0`, `v0.2.1`, etc. Increase both versionName and versionCode for each future release. Releases of an existing tag are not overwritten. Stable signing and versioned releases must be configured before in-app updates can succeed.

If secrets are **not** configured, GitHub builds experimental debug APKs only and publishes `experimental-latest` (prerelease). These APKs are for fresh testing installations: to install one over an older differently signed debug build, you must first uninstall the old app. Back up any settings if needed.

## Build

Requirements: Android Studio / SDK 35, JDK 17, Gradle 8.10.2; Android API 26+ device.

Workflow: [Build Android APK](../../actions/workflows/android-apk.yml) compiles, runs protocol unit tests, publishes an APK artifact and, when appropriately signed, makes a GitHub Release.

No analytics, cloud log upload or BMS writes other than read-only requests. Internet is used only to check and install published updates.
