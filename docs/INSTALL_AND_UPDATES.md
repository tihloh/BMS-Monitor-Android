# Installing and updating BMS Monitor

## Fixing "cannot find ECO039E"
1. Completely force-close the original Gentai app and nRF Connect.
2. Switch on Android Bluetooth. On Android 12+, approve Nearby devices permission for BMS Monitor.
3. Tap **Scan / Connect**. The progress indicator remains visible during the 16-second scan.
4. Stand within a meter of the battery. Tap **ECO039E** or **BMC1_039E** in the discovered device list.
5. When discovery fails, the UI displays actionable troubleshooting status. Tap **Copy log** to report a problem.
6. If the scan returns zero advertisements, choose **Allow & retry** when asked for Location permission, then turn on the phone's system **Location** switch. Some Android 12–16 devices/OEM builds do not supply BLE scan results without both.
7. The app first scans in low-latency mode and retries with Android's default scan API if nothing is reported after 9 seconds. A completed scan takes at most about 23 seconds.
8. **Scan troubleshooting** opens Location settings, app permissions, or instructions. For direct connection, enter the BMS's BLE address obtained independently from nRF Connect.
9. **Copy log** reports scan start, first detected advertisement, scan failure codes, and final advertisement count. This helps distinguish Android permission problems from app parsing problems.

Location is used only to support local Bluetooth device discovery. The app does not read GPS coordinates or upload location data.

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
