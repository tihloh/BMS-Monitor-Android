# BMS Monitor Android

Experimental **read-only** Gentai/ECO-style Bluetooth battery monitor for Android 8.0 and later.

## Version 0.2.2
- Fix BLE discovery on Android 12–16 by requesting Nearby devices **and precise Location** (without the `neverForLocation` filter).
- Detect disabled phone Location and guide users to Android settings; GPS coordinates are never collected.
- Add a one-tap **Connect directly to ECO039E** button prefilled with the device address from the original nRF Connect screenshot.
- Auto-connect when a nearby device advertises as `ECO039E`, while keeping manual device selection.
- Scan failure diagnostics remain visible instead of incorrectly reporting zero nearby devices.

## Previous Version 0.2.0
- BLE scanner with explicit nearby-device permissions, scan progress, signal strength, and diagnostic messages.
- Connects to observed Gentai `ECO039E` / `BMC1_039E` device using service `0x0001`, write `0x0002`, notify `0x0003`.
- Decodes fragmented BLE telemetry packets: voltage, current, power, SOC, remaining/rated capacity and compatible cell voltages.
- Cleaner compact dashboard, charging state and live-update timestamp, diagnostics copy.
- GitHub Release update checker and Android PackageInstaller with user confirmation, adapted from the user's POS app design.
- Experimental debug APK built by GitHub Actions after test pass; separately supported **signed** versioned releases for reliable future updates.

## APK
[Get the latest experimental APK](https://github.com/tihloh/BMS-Monitor-Android/releases/tag/experimental-latest).

**Important:** Experimental debug APKs may require uninstall/reinstall between versions because CI signs them with ephemeral debug certificates. To activate in-place auto-updating, set up stable signing and publish a versioned release. See [docs/INSTALL_AND_UPDATES.md](docs/INSTALL_AND_UPDATES.md).

## Device
- Primary BLE service: `00000001-0000-1000-8000-00805f9b34fb`
- Write: `00000002-0000-1000-8000-00805f9b34fb`
- Notify: `00000003-0000-1000-8000-00805f9b34fb`
- Telemetry requests: `AA21002100` and `AA22002200` (read-only command types 0x21 and 0x22).
- A screenshot-captured response matched Gentai's ~25.99V, -1.15A, 19% SOC and 19.48Ah remaining.

No remote server stores battery data; Internet is used only for GitHub Release update checks/downloads. No protection, MOSFET, balancing, or charging configuration writes are implemented.

**Safety:** Experimental software must not be used as the sole mechanism for battery protection, AC transfer, or charging decisions.

Related protocol reference: [ericbarch/ecoworthy-bms-exporter](https://github.com/ericbarch/ecoworthy-bms-exporter).
