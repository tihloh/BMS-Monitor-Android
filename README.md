# Battery Monitor for Android

A lightweight, read-only Bluetooth Low Energy (BLE) battery monitor for Android 8.0 and newer. It discovers nearby BLE devices and attempts to interpret supported BMS telemetry without modifying battery settings.

## Features

- Auto-scan at startup, optional reconnection to the last BMS, and manual device selection that takes precedence over automatic reconnection.
- Device connections and a compact battery dashboard on separate pages. The dashboard is hidden until the BMS is connected.
- Voltage, current, state of charge, power, remaining capacity, supported cells, and diagnostics when offered by the battery protocol.
- Support for compatible Gentai/ECO, JBD/Xiaoxiang, and Bluetooth Battery Service devices; supported measurements vary by device/firmware.
- Persistent themes: **Follow system, Light, Dark, AMOLED Black, Ocean, Forest**. Change them via **Updates & About → Appearance → Choose app theme**. Themes apply immediately without disconnecting the BMS.
- GitHub Releases update checks and Android-approved in-place installation for APKs signed with the same permanent key.
- Bluetooth diagnostic log with copy function and an update notification preference.

## Installation and updates

**[Download the latest signed Android APK](https://github.com/tihloh/BMS-Monitor-Android/releases/latest)**.

On an existing compatible signed installation, download/install the newer APK or use the in-app update action. Android may ask you to authorize the installation. Keep the same package ID and signing certificate for in-place upgrades. An APK signed with a different certificate cannot update the installed package directly.

For release signing and deployment information, see [Installation and Updates](docs/INSTALL_AND_UPDATES.md). GitHub Actions runs release unit tests, builds, verifies the signature and package/version, and publishes an APK on updates to `main`.

## Supported protocols and diagnostics

The app probes compatible BLE services and chooses a decoder based on what the remote device provides. Some Gentai extended telemetry is provisional; refer to the in-app notice and compare to the battery vendor app when in doubt. No protocol implementation guarantees compatibility with every BMS.

Example Gentai-compatible BLE identifiers:
- Service: `00000001-0000-1000-8000-00805f9b34fb`
- Write: `00000002-0000-1000-8000-00805f9b34fb`
- Notify: `00000003-0000-1000-8000-00805f9b34fb`

Read-only request examples: `AA21002100`, `AA22002200`.

## Privacy and safety

Bluetooth scan and connection work locally; internet access is used to check and fetch GitHub Releases. The monitor does not remotely manage the pack, MOSFETs, or charging, and is **not a replacement for the BMS or independent electrical protection**.

Additional documents: [Telemetry](docs/TELEMETRY.md), [Dashboard](docs/DASHBOARD.md), [Testing](docs/TESTING.md).
