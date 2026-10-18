# Dashboard connection gating
The Dashboard tab and all battery metrics are hidden until the application reports an active, telemetry-ready BLE connection. The app opens **Connections** on launch. On connection, Dashboard appears and opens automatically. On disconnect, the app returns to **Connections** and clears previous readings; **Updates & About** remains available offline.
