# Consistent signing for automatic updates

Android only installs an APK over an existing app if **the application ID and signing certificate match**, and versionCode increases.

Our existing GitHub Actions debug APK uses temporary signing unless configured otherwise. In-place updates between independently built debug APKs can fail.

## One-time setup, as in POS
Generate and securely store a signing keystore on your own machine and configure four GitHub Actions repository secrets (never commit passwords/keystores to this repository):
- `ANDROID_KEYSTORE_BASE64`: base64 of your keystore file.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow automatically uses this key on new builds. Install the **first APK with stable signing**, then subsequent releases with increasing versionCode can update in-place through Android PackageInstaller (with user confirmation). Old experimental APKs signed with a different certificate must be uninstalled first. Your physical BMS and its settings are unaffected.

## Versioning
Bump `versionCode` and `versionName` in `app/build.gradle` each release. The GitHub workflow publishes `vX.Y.Z` prereleases and also refreshes `experimental-latest`. The app compares semantic versions across releases and downloads only the APK from this repository.

The application never silently installs an APK or bypasses Android confirmation.
