# BMS Monitor Android

Experimental, **read-only** Bluetooth Low Energy monitor for Gentai/ECO-style 24V LiFePO4 battery packs.

## Device confirmed during initial research
- Gentai BMS broadcast: `ECO039E` (original app names it `BMC1_039E`)
- Primary GATT service: `00000001-0000-1000-8000-00805f9b34fb`
- Write characteristic: `00000002-0000-1000-8000-00805f9b34fb`
- Notify characteristic: `00000003-0000-1000-8000-00805f9b34fb`
- Read-only telemetry requests: `AA 21 00 21 00` for battery information and `AA 22 00 22 00` for cell voltages.
- Observed battery response was fragmented across two BLE notifications. The app assembles packets before decoding.
- The recorded response decoded to 25.99 V, -1.15 A, 19% SOC, 19.48 Ah remaining, 100 Ah capacity. These were close to the manufacturer's app readings.

The packet mapping follows a community reverse-engineered interface, tested here against one Gentai device. **This application is experimental; do not rely on it for battery safety, charging, or load-cutoff decisions.** It does not expose BMS configuration controls.

## Features
- Scan nearby BLE devices; tap `ECO039E` to connect.
- Subscribe to GATT notifications and poll basic information every 5 seconds.
- Voltage, current, power, SOC, remaining/rated capacity, charging/discharging status.
- Periodic cell-voltage request and approximate min/max voltage difference where the device returns compatible data.
- Raw hexadecimal log with one-tap copy for diagnosis.
- Foreground-only operation; no accounts, cloud service, internet permission, or background location collection.

## Android / build
- Android 8.0+ (API 26).
- Requires Nearby devices permission on Android 12+; Location permission for BLE scanning on older Android versions.
- **GitHub Actions** builds a debug APK on every push to `main` and can be started manually in Actions → Build Android APK.
- From the successful run, download `BMS-Monitor-debug-APK` under Artifacts.
- Main branch builds also publish the test APK on the [experimental-latest release](../../releases/tag/experimental-latest).
- Local compilation: Android Studio with JDK 17 / Gradle 8.10.2, or `gradle :app:assembleDebug` with an Android SDK.

## Safety and protocol notes
This app sends **only** two documented telemetry requests (command 0x21 and 0x22). No protection, balancing, MOSFET, or configuration writes are implemented. Telemetry compatibility has been checked only against the screenshots supplied by the device owner. Protocol variations remain possible.

Reverse-engineered packet reference: [ericbarch/ecoworthy-bms-exporter](https://github.com/ericbarch/ecoworthy-bms-exporter) (MIT licensed; see upstream for notices). This app uses its own parser implementation.
