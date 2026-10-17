# Prototype testing checklist

1. Install the debug APK and grant Nearby Devices on Android 12+ (or Location permission on older Android).
2. Force-close the original Gentai battery app, so it doesn't occupy the BLE connection.
3. Open BMS Monitor, choose **Scan for BMS**, and tap **ECO039E**.
4. Confirm live voltage, current, SOC, and capacity match the Gentai app within the time difference.
5. Check that cell readings appear when the BMS supports read-only command `0x22`.
6. Copy the on-screen Bluetooth log if any reading fails; do not try BMS configuration writes.

The app is for exploration and has not been safety-certified. Do not use its values to control battery protection.
