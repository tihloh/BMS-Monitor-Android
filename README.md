# BMS Monitor Android

Experimental **read-only** Gentai/ECO-style Bluetooth battery monitor for Android 8.0 and later.

## Version 0.3.3
- Fixed update detection: GitHub Actions now publishes a semver-tagged experimental pre-release for each new version, allowing older apps to discover an update.
- Debug builds check versioned experimental prereleases; signed builds only check stable signed releases.
- Debug APK downloads are opened in the browser rather than attempting an incompatible in-place installation. The POS-style in-app installer remains enabled for signed versions.
- Background update checks now recognize experimental prereleases on debug installs.

## Previous features (0.2.x)
- BLE scanner with explicit nearby-device permissions, scan progress, signal strength, and diagnostic messages.
- Connects to observed Gentai `ECO039E` / `BMC1_039E` device using service `0x0001`, write `0x0002`, notify `0x0003`.
- Decodes fragmented BLE telemetry packets: voltage, current, power, SOC, remaining/rated capacity and compatible cell voltages.
- Cleaner compact dashboard, charging state and live-update timestamp, diagnostics copy.
- GitHub Release update checker and Android PackageInstaller with user confirmation, adapted from the user's POS app design.
- Experimental debug APK built by GitHub Actions after test pass; separately supported **signed** versioned releases for reliable future updates.

## APK
[Get the latest experimental APK](https://github.com/tihloh/BMS-Monitor-Android/releases/tag/experimental-latest).

**Important:** The checker now detects experimental versioned APK releases. **Detection does not mean Android can replace a debug APK in place.** Debug APKs may require uninstall/reinstall because GitHub Actions signs them with ephemeral debug certificates. To activate in-place auto-updating, set up stable signing and publish a versioned release. See [docs/INSTALL_AND_UPDATES.md](docs/INSTALL_AND_UPDATES.md).

## Device
- Primary BLE service: `00000001-0000-1000-8000-00805f9b34fb`
- Write: `00000002-0000-1000-8000-00805f9b34fb`
- Notify: `00000003-0000-1000-8000-00805f9b34fb`
- Telemetry requests: `AA21002100` and `AA22002200` (read-only command types 0x21 and 0x22).
- A screenshot-captured response matched Gentai's ~25.99V, -1.15A, 19% SOC and 19.48Ah remaining.

No remote server stores battery data; Internet is used only for GitHub Release update checks/downloads. No protection, MOSFET, balancing, or charging configuration writes are implemented.

**Safety:** Experimental software must not be used as the sole mechanism for battery protection, AC transfer, or charging decisions.

Related protocol reference: [ericbarch/ecoworthy-bms-exporter](https://github.com/ericbarch/ecoworthy-bms-exporter).
