# BMS Monitor Android

Experimental, read-only BLE battery dashboard for Gentai BMS, including ECO039E.

## Changes in 0.2.0
- Automatically requests BLE permissions and begins scanning; diagnostics distinguish missing permissions from an empty scan.
- Prioritizes known ECO/BMC/BMS devices and devices advertising services `0xFF00` or `0x0001`.
- Show all nearby BLE devices, manual retry, readable scan status, signal strength, and last-device reconnection.
- Compact battery dashboard for live SOC, voltage, current, power, capacity, and optional cell voltages.
- Bluetooth debug log is hidden until requested, with one-tap copy.
- On-open GitHub release check and hourly background update checks; download/stage an APK internally via Android PackageInstaller when permitted.
- No modification of BMS protections or settings. Known telemetry requests only (`0x21` and `0x22`).

## Install
Get the APK from [Releases](../../releases/latest). Android 8+ required. Grant Nearby devices when requested on Android 12+, or Location and enable Location services on Android 8–11.

The original Gentai app must be disconnected/force-stopped while connecting because many BLE batteries support only one central at a time.

**Upgrade signing**: normal Android in-place upgrades require all APK versions be signed with the SAME key. GitHub-hosted ephemeral debug builds do not preserve that key. Until you configure persistent signing secrets, you may need to uninstall the previous experimental APK before installing a new one. This deletes any app-local settings. Do not disable Android signature checks. Once persistent signing is configured, future builds with the same key can upgrade in place.

### Persistent signing (required for reliable auto-install)
Create a private Android keystore on your own trusted machine. **Never put a private keystore or password in Git or chat.**
Set these GitHub repository Actions secrets:
- `BMS_KEYSTORE_BASE64`: base64 of the private keystore
- `BMS_SIGNING_STORE_PASSWORD`
- `BMS_SIGNING_KEY_ALIAS`
- `BMS_SIGNING_KEY_PASSWORD`

GitHub Actions uses the keystore only when all four secrets exist. The app checks the **latest non-prerelease GitHub Release**, compares semantic versions, stages the APK privately, and opens Android's system install confirmation. It checks on launch and periodically, when network connectivity allows. Auto-install is not truly silent on normal Android phones.

### Limitations
This app has not been validated against a live battery beyond the recorded sample and should never control protective cutoffs, charging, or critical loads. Automatic installation is Android-dependent and may require foreground confirmation. Continuous/background BLE monitoring is not implemented.

## Source
Android Java with AndroidX WorkManager. Tests check the Gentai packet captured during investigation. The protocol is a reverse-engineered ECO-compatible BLE interface; future devices may differ.
