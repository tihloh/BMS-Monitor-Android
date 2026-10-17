# Installing and updating BMS Monitor

## Fixing "cannot find ECO039E"
1. Completely force-close the original Gentai app and nRF Connect.
2. Enable Android Bluetooth **and the phone Location switch**. On Android 12+, approve both **Nearby devices** and **Precise location** for BMS Monitor. The app does not read GPS coordinates. Android BLE scanning can return zero advertisements if required Location permission or device location services are off.
3. Tap **Scan / Connect**. The progress indicator runs during a 19-second unfiltered scan, with a fallback scan mode at 8.5 seconds if no advertisements appear.
4. Stand within a meter of the battery. Tap **ECO039E** or **BMC1_039E** in the discovered device list.
5. When discovery fails, the UI displays actionable troubleshooting status. Tap **Copy log** to report a problem.
6. Check the on-screen **Permissions / Location settings** help and **Bluetooth diagnostics**. The settings shortcut explains how to grant precise Location and Nearby devices permission. Confirm Gentai/nRF Connect isn't maintaining an active connection.

## Finding updates in experimental builds (fixed in v0.3.3)
Previous versions checked only semver release tags, while CI only updated the `experimental-latest` rolling tag. Update discovery always returned "No published versioned releases found." Starting v0.3.3, the CI creates a separate `vX.Y.Z` **prerelease** every time the version changes. This means even the older app's manual Check for updates can find the newer APK.

- Experimental debug builds check **prerelease** tags; production signed builds ignore prereleases.
- In an experimental build, tapping **Get experimental APK** opens GitHub for manual installation because CI's ephemeral debug signing keys cannot guarantee an in-place upgrade.
- The background update job checks the appropriate release channel and posts a notification for a newer version (notification permission required on Android 13+).
- After the project has a safely backed-up permanent signing key configured as GitHub Actions secrets, distribute properly signed stable releases for POS-style in-app updates.
- **Increase versionName and versionCode for each new binary release**; reusing a version will not trigger an update prompt.

## APK builds
- **Build Android APK** runs automatically on pull requests and pushes to main; it generates an experimental debug APK.
- Debug APK: [experimental-latest](https://github.com/tihloh/BMS-Monitor-Android/releases/tag/experimental-latest).
- **Debug builds are NOT update-compatible across separate GitHub Actions runners**, which use different Android debug signing certificates. You may need to uninstall the old debug app and reinstall a new debug build.

## Secure persistent signatures for real in-app auto-updates
Like the POS application, BMS Monitor checks public GitHub Releases for a newer numeric version when opened (at most every 12 hours) and offers an Android-confirmed internal PackageInstaller update. Only versioned releases matching `vX.Y.Z` are considered; the experimental-latest debug build is deliberately excluded.

To allow seamless installation over existing signed releases, all versions **must use the SAME private signing keystore**. Do not put the keystore or passwords into Git or chat.

### One-time setup
Generate and safely back up your own Android release signing keystore (e.g. from Android Studio's Generate Signed Bundle/APK wizard or keytool). In GitHub repository **Settings → Secrets and variables → Actions**, configure:
- `ANDROID_KEYSTORE_BASE64` — base64-encoded keystore bytes
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — key alias
- `ANDROID_KEY_PASSWORD` — key password

Run **Actions → Publish signed APK → Run workflow**, choose a version name (e.g. 0.2.1) and a versionCode greater than the previous release. The workflow publishes `v0.2.1` with a signed APK. Subsequent versioned releases must reuse the same key and increment versionCode.

### First migration from experimental debug
The 0.1.0 experimental APK was signed with a different, temporary debug key. Back up anything you need from the app (currently no user-managed cloud storage), **uninstall the debug app once**, and install the first stable signed APK. Future stable releases can then update in place.

Android always requires installation approval for sideloaded APK updates. BMS Monitor never bypasses this system restriction.


## Version 0.2.2: BLE discovery repair
- Manifest: requests COARSE/FINE Location on Android 12+ as well as Nearby devices; no GPS readings collected.
- Before scan: check Bluetooth, permissions, and system Location switch with user-facing instructions.
- Unfiltered low-latency scan with a simpler retry after 8.5 seconds if Android provides no advertisements.
- Show every BLE device, its RSSI and address, and highlight likely BMS advertisements.
- Bluetooth diagnostics now show the first detected advertisements and scan failures.
- **Important**: Android permission approval and an actual scan on the user's handset remain necessary to validate the fix. A successful GitHub build does not prove detection works on a particular phone.

## Automatic update notes
The POS-inspired update checker is implemented: checks versioned GitHub releases, prompts the user, downloads a compatible APK, and requests user approval through PackageInstaller. Hourly best-effort update checks can post a notification. `experimental-latest` debug APKs are excluded from in-place updates because CI debug signatures are not stable. For reliable in-app auto-updates, set private signing credentials in GitHub Actions once and publish numbered releases, reusing the same key forever. No keystore or passwords should be checked into Git.

## v0.3.0 Android BLE scanner compatibility

- Unfiltered low-latency BLE scanner first, then alternate legacy LE scanning automatically after 8 seconds without results.
- Scans run for up to 20 seconds, with detailed status and a copyable diagnostic log.
- Android 12+ requires Nearby Devices permission. Some phones also require Location access and the Location switch ON. The app does not store geographic coordinates.
- If scanning discovers no devices but nRF Connect sees ECO039E, enter the BMS Bluetooth address from nRF Connect into the Direct BLE field and tap **Direct**.
- Telemetry remains read-only. This repair has passed CI but must be validated with a real Gentai battery.

**Experimental debug APK signing:** GitHub Actions debug builds from separate runners can have different certificates. If an old debug APK won't install over the top, uninstall it before installing v0.3.0. True automatic in-place updates require one consistent release signing key and published numeric GitHub releases.
