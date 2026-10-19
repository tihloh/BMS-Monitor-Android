package com.tihloh.bmsmonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;
import android.app.NotificationManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity implements BleClient.Listener, ReleaseUpdater.Callback {
    private static final int PERMISSIONS = 10, BLUETOOTH_ENABLE = 11;
    private static final int NAVY = Color.rgb(27, 43, 58);
    private static final int MUTED = Color.rgb(99, 116, 132);
    private static final int CYAN = Color.rgb(0, 124, 145);
    private static final int BACK = Color.rgb(242, 247, 249);
    private static final int BORDER = Color.rgb(222, 231, 236);
    private final Set<String> discovered = new HashSet<>();
    private BleClient ble;
    private ReleaseUpdater updater;
    private LinearLayout deviceList, cellList, logBox, scanCard;
    private ScrollView mainScroll;
    private LinearLayout dashboardView, connectionView, aboutView;
    private Button dashboardTab, connectionTab, aboutTab;
    private int currentPage = 1;
    /** The dashboard is available only after a BMS GATT connection is ready. */
    private boolean bmsConnected = false;
    private TextView liveIndicator, cellFreshness, cellSummary;
    private TextView protocolLabel, deviceName, detailsAge, batteryTemperature,
            protectionStatus, mosCharge, mosDischarge, balancingStatus,
            cycleCount, manufactureDate, firmwareVersion, reportedCells, usageEstimate,
            batteryHealth, extendedNotice;
    private long lastDetailsReadingMs = -1;
    private String currentProtocol = "Not identified";
    private String connectedDeviceLabel = "Unavailable";
    private long lastBasicReadingMs = -1;
    private long lastCellsReadingMs = -1;
    private long connectedSinceMs = -1;
    private String telemetryCondition = "Waiting for battery data";
    private final Handler freshnessHandler = new Handler(Looper.getMainLooper());
    private final Runnable freshnessTicker = new Runnable() {
        @Override public void run() {
            updateLiveIndicator();
            freshnessHandler.postDelayed(this, 1000);
        }
    };
    private TextView connection, scanHint, firmwareHint, soc, status, voltage, current, power,
            remaining, capacity, updated, cellHeading, logText, totalFound;
    private Button scanButton, disconnectButton;
    private EditText manualMac;
    private ProgressBar progress;
    private Switch notificationSwitch;
    private final StringBuilder logs = new StringBuilder();
    private boolean pendingDirectConnection;
    private boolean connecting;
    private boolean autoDiscoveryActive;
    private boolean autoDirectAttempted;
    private boolean manualDisconnect;
    // True while the user is scanning for or choosing a different BMS.
    // This always takes precedence over saved-device recovery.
    private boolean userConnectionOverride;
    private String requestedDeviceAddress = "";
    private int reconnectRetries;
    private String autoReconnectAddress = "";
    private final Handler autoConnectHandler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectTask = () -> {
        if (!manualDisconnect && !userConnectionOverride
                && !connecting && ble != null && !ble.isReady()) {
            beginAutomaticDiscovery();
        }
    };
    private int visibleCount;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        ble = new BleClient(this, this);
        updater = new ReleaseUpdater(this, this);
        drawUi();
        onStatus("Not connected", false);
        onLog("Ready. Scanning requires Bluetooth/Nearby Devices permission.");
        UpdateJobService.schedule(getApplicationContext());
        boolean fromNotification = getIntent() != null && getIntent().getBooleanExtra("check_updates_now", false);
        if (fromNotification) showPage(2);
        updater.check(fromNotification);
        if (fromNotification) getIntent().removeExtra("check_updates_now");
        // Start only once per Activity creation, after the UI exists. A scan never
        // replaces or disconnects an existing live GATT session.
        autoConnectHandler.post(this::beginAutomaticDiscovery);
    }

    private void drawUi() {
        ScrollView scroll = new ScrollView(this);
        mainScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACK);
        LinearLayout root = vertical();
        root.setPadding(dp(16), dp(22), dp(16), dp(30));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text(getString(R.string.app_name), 25, NAVY, true);
        root.addView(title);
        TextView subtitle = text("UNIVERSAL BLE BATTERY MONITOR", 11, CYAN, true);
        subtitle.setLetterSpacing(0.06f);
        root.addView(subtitle, below(0, 5));
        LinearLayout tabs = horizontal();
        dashboardTab = button("Dashboard", true);
        connectionTab = button("Connections", false);
        aboutTab = button("Updates & About", false);
        tabs.addView(dashboardTab, equalButton());
        tabs.addView(connectionTab, equalButton());
        tabs.addView(aboutTab, equalButton());
        dashboardTab.setOnClickListener(v -> showPage(0));
        connectionTab.setOnClickListener(v -> showPage(1));
        aboutTab.setOnClickListener(v -> showPage(2));
        root.addView(tabs, below(0, 14));
        dashboardView = vertical();
        connectionView = vertical();
        aboutView = vertical();
        root.addView(dashboardView, below(0, 12));
        root.addView(connectionView, below(0, 12));
        root.addView(aboutView, below(0, 12));
        connectionView.addView(text("Connection Manager", 20, NAVY, true));
        aboutView.addView(text("Updates & About", 20, NAVY, true));

        LinearLayout connectionCard = panel();
        LinearLayout topLine = horizontal();
        connection = text("Not connected", 16, NAVY, true);
        topLine.addView(connection, new LinearLayout.LayoutParams(0, dp(30), 1));
        connection.setGravity(Gravity.CENTER_VERTICAL);
        TextView version = text("v" + BuildConfig.VERSION_NAME, 11, MUTED, true);
        version.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topLine.addView(version);
        connectionCard.addView(topLine);
        status = text("Connect to a Bluetooth battery to view live readings.", 13, MUTED, false);
        connectionCard.addView(status, below(0, 5));
        connectionView.addView(connectionCard, below(0, 12));

        Button gentaiConnect = button("Connect to saved BLE address", true);
        gentaiConnect.setTextSize(15);
        gentaiConnect.setOnClickListener(v -> connectManual());
        connectionView.addView(gentaiConnect, below(0, 11));
        LinearLayout actions = horizontal();
        scanButton = button("Scan for devices", true);
        scanButton.setOnClickListener(v -> {
            stopAutomaticHandoff(); // Browsing another device cancels previous-device recovery.
            beginScan();
        });
        disconnectButton = button("Disconnect", false);
        disconnectButton.setOnClickListener(v -> {
            stopAutomaticHandoff();
            manualDisconnect = true;
            connecting = false;
            ble.disconnect();
            onStatus("Disconnected by user", false);
            status.setText("Disconnected manually. Scan or Connect when ready.");
        });
        actions.addView(scanButton, equalButton());
        actions.addView(disconnectButton, equalButton());
        connectionView.addView(actions, below(0, 10));

        scanCard = panel();
        LinearLayout scanRow = horizontal();
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        progressLp.rightMargin = dp(8);
        scanRow.addView(progress, progressLp);
        totalFound = text("Nearby Bluetooth devices", 14, NAVY, true);
        scanRow.addView(totalFound);
        scanCard.addView(scanRow);
        scanHint = text("Scan shows all Bluetooth LE devices. Grant Nearby devices and precise Location when prompted.", 12, MUTED, false);
        scanCard.addView(scanHint, below(0, 7));
        Button permissionsHelp = button("Permissions / Location settings", false);
        permissionsHelp.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Make Bluetooth scanning work")
                .setMessage("Allow Nearby devices and precise Location; turn on Bluetooth and the Location switch. The app does not request your GPS position. Disconnect Gentai or nRF Connect before connecting.")
                .setPositiveButton("App permissions", (d, w) -> startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + getPackageName()))))
                .setNeutralButton("Location switch", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("Close", null).show());
        scanCard.addView(permissionsHelp, below(0, 7));
        deviceList = vertical();
        scanCard.addView(deviceList, below(0, 4));
        LinearLayout directRow = horizontal();
        manualMac = new EditText(this);
        manualMac.setSingleLine(true);
        manualMac.setTextSize(13);
        manualMac.setHint("BLE address (XX:XX:XX:XX:XX:XX)");
        manualMac.setText(getPreferences(0).getString("last_bms", ""));
        manualMac.setSelectAllOnFocus(true);
        manualMac.setPadding(dp(9), 0, dp(9), 0);
        manualMac.setBackground(background(Color.rgb(232, 240, 244), 10, 0));
        LinearLayout.LayoutParams edit = new LinearLayout.LayoutParams(0, dp(44), 1);
        edit.rightMargin = dp(7);
        directRow.addView(manualMac, edit);
        Button manualConnect = button("Direct", true);
        manualConnect.setOnClickListener(v -> connectManual());
        directRow.addView(manualConnect, new LinearLayout.LayoutParams(dp(98), dp(44)));
        scanCard.addView(text("DIRECT BLE CONNECTION · Enter a compatible BLE device address:", 12, MUTED, true), below(0, 10));
        scanCard.addView(directRow, below(0, 5));
        connectionView.addView(scanCard, below(0, 10));

        LinearLayout socPanel = panel();
        LinearLayout liveHeader = horizontal();
        liveHeader.addView(text("STATE OF CHARGE", 11, MUTED, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        liveIndicator = text("WAITING FOR DATA", 11, MUTED, true);
        liveIndicator.setGravity(Gravity.END);
        liveHeader.addView(liveIndicator);
        socPanel.addView(liveHeader);
        soc = text("-- %", 44, CYAN, true);
        socPanel.addView(soc, below(0, 5));
        updated = text("No readings received yet", 12, MUTED, false);
        socPanel.addView(updated);
        dashboardView.addView(socPanel, below(0, 15));

        LinearLayout metrics1 = horizontal();
        LinearLayout voltsCard = metric("TOTAL VOLTAGE", "-- V"); voltage = (TextView) voltsCard.getChildAt(1);
        LinearLayout a = metric("CURRENT", "-- A"); current = (TextView) a.getChildAt(1);
        metrics1.addView(voltsCard, equalCard()); metrics1.addView(a, equalCard());
        dashboardView.addView(metrics1, below(0, 9));

        LinearLayout metrics2 = horizontal();
        LinearLayout w = metric("POWER", "-- W"); power = (TextView) w.getChildAt(1);
        LinearLayout ah = metric("REMAINING", "-- Ah"); remaining = (TextView) ah.getChildAt(1);
        metrics2.addView(w, equalCard()); metrics2.addView(ah, equalCard());
        dashboardView.addView(metrics2, below(0, 9));

        LinearLayout more = panel();
        capacity = text("Rated capacity: -- Ah", 14, NAVY, true);
        more.addView(capacity);
        usageEstimate = text("Estimated runtime: —", 12, MUTED, false);
        more.addView(usageEstimate, below(0, 5));
        dashboardView.addView(more, below(0, 9));

        LinearLayout diagnostics = panel();
        diagnostics.addView(text("BMS status & sensors", 15, NAVY, true));
        protocolLabel = text("Protocol: Not identified", 12, MUTED, false);
        diagnostics.addView(protocolLabel, below(0, 5));
        detailsAge = text("Waiting for battery status", 11, MUTED, false);
        diagnostics.addView(detailsAge, below(0, 3));
        deviceName = addDetail(diagnostics, "Device", "Unavailable");
        batteryTemperature = addDetail(diagnostics, "Temperatures", "Unavailable");
        reportedCells = addDetail(diagnostics, "Reported cells", "Unavailable");
        protectionStatus = addDetail(diagnostics, "Active protections", "Unavailable");
        mosCharge = addDetail(diagnostics, "Charge MOSFET", "Unavailable");
        mosDischarge = addDetail(diagnostics, "Discharge MOSFET", "Unavailable");
        balancingStatus = addDetail(diagnostics, "Balancing", "Unavailable");
        cycleCount = addDetail(diagnostics, "Cycle count", "Unavailable");
        batteryHealth = addDetail(diagnostics, "Battery health", "Unavailable");
        firmwareVersion = addDetail(diagnostics, "BMS software", "Unavailable");
        manufactureDate = addDetail(diagnostics, "Manufacture date", "Unavailable");
        extendedNotice = text("", 11, MUTED, false);
        extendedNotice.setVisibility(View.GONE);
        diagnostics.addView(extendedNotice, below(0, 7));
        dashboardView.addView(diagnostics, below(0, 9));

        LinearLayout cells = panel();
        cellHeading = text("Individual cell voltages", 15, NAVY, true);
        cells.addView(cellHeading);
        cellSummary = text("Cell minimum / maximum / average: unavailable", 12, MUTED, false);
        cells.addView(cellSummary, below(0, 4));
        cellFreshness = text("No cell readings yet", 11, MUTED, false);
        cells.addView(cellFreshness, below(0, 3));
        cellList = vertical();
        cellList.addView(text("Awaiting a supported response from the BMS.", 12, MUTED, false));
        cells.addView(cellList, below(0, 9));
        dashboardView.addView(cells, below(0, 10));

        LinearLayout updatePanel = panel();
        updatePanel.addView(text("App updates", 15, NAVY, true));
        firmwareHint = text("Checks on launch and approximately hourly in the background. Android confirms installation.", 12, MUTED, false);
        updatePanel.addView(firmwareHint, below(0, 5));
        Button check = button("Check for updates", false);
        check.setOnClickListener(v -> updater.check(true));
        updatePanel.addView(check, below(0, 9));
        notificationSwitch = new Switch(this);
        notificationSwitch.setTextSize(13);
        updatePanel.addView(notificationSwitch, below(0, 8));
        refreshNotificationSwitch();
        aboutView.addView(updatePanel, below(0, 10));

        logBox = panel();
        LinearLayout logHeader = horizontal();
        TextView logTitle = text("Bluetooth diagnostics", 15, NAVY, true);
        logHeader.addView(logTitle, new LinearLayout.LayoutParams(0, dp(44), 1));
        logTitle.setGravity(Gravity.CENTER_VERTICAL);
        Button copy = button("Copy log", false);
        copy.setTextSize(12);
        copy.setOnClickListener(v -> {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("BMS diagnostic log", logs.toString()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });
        logHeader.addView(copy);
        logBox.addView(logHeader);
        logText = text("Waiting for events…", 11, MUTED, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setMaxLines(10);
        logBox.addView(logText);
        connectionView.addView(logBox, below(0, 10));

        TextView footer = text("Read-only battery monitor. Not a substitute for battery protection.", 11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout aboutCard = panel();
        aboutCard.addView(text(getString(R.string.app_name), 18, NAVY, true));
        aboutCard.addView(text("Version " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", 13, MUTED, false), below(0, 5));
        aboutCard.addView(text("Developer: Christian Bustamante", 13, NAVY, true), below(0, 6));
        aboutCard.addView(text("App ID: com.tihloh.bmsmonitor", 12, MUTED, false), below(0, 4));
        aboutCard.addView(text("Read-only Bluetooth LE battery monitor. Updates use Android PackageInstaller; Android may require installation approval.", 12, MUTED, false), below(0, 8));
        Button source = button("GitHub releases", false);
        source.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/tihloh/BMS-Monitor-Android/releases"))));
        aboutCard.addView(source, below(0, 9));
        aboutView.addView(aboutCard, below(0, 10));
        aboutView.addView(footer, below(0, 15));
        showPage(currentPage);
    }

    private TextView addDetail(LinearLayout container, String label, String value) {
        TextView t = text(label + ": " + value, 13, NAVY, false);
        t.setVisibility(View.GONE);
        container.addView(t, below(0, 5));
        return t;
    }

    /** Hide unsupported fields instead of filling a long card with Unavailable. */
    private static void setDetail(TextView view, String label, String value) {
        if (view == null) return;
        boolean hasValue = value != null && !value.trim().isEmpty() && !"Unavailable".equals(value);
        view.setVisibility(hasValue ? View.VISIBLE : View.GONE);
        if (hasValue) view.setText(label + ": " + value);
    }

    private void refreshExtendedNotice() {
        if (extendedNotice == null) return;
        String notice = "";
        if ("Gentai / ECO".equals(currentProtocol)) {
            notice = "Gentai extended values are provisional; verify against the Gentai app. "
                    + "MOSFET, balancing and protection details have no verified decoder yet.";
        } else if ("Standard BLE Battery Service".equals(currentProtocol)) {
            notice = "This Bluetooth profile reports only battery level.";
        }
        extendedNotice.setText(notice);
        extendedNotice.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private boolean systemNotificationsAllowed() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return manager != null && (Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled());
    }

    private void refreshNotificationSwitch() {
        if (notificationSwitch == null) return;
        boolean preferred = getSharedPreferences("bms_updates", MODE_PRIVATE)
                .getBoolean("notifications_enabled", true);
        boolean allowed = (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && systemNotificationsAllowed();
        notificationSwitch.setOnCheckedChangeListener(null);
        notificationSwitch.setChecked(preferred && allowed);
        notificationSwitch.setText(preferred && allowed ? "Update notifications enabled" :
                preferred ? "Update notifications blocked in Android settings" : "Update notifications disabled");
        notificationSwitch.setOnCheckedChangeListener((view, checked) -> {
            getSharedPreferences("bms_updates", MODE_PRIVATE).edit()
                    .putBoolean("notifications_enabled", checked).apply();
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 93);
            else if (checked && !systemNotificationsAllowed())
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            refreshNotificationSwitch();
        });
    }

    private void showPage(int page) {
        // Neither the dashboard tab nor its data is visible while disconnected.
        if (page == 0 && !bmsConnected) page = 1;
        currentPage = page;
        dashboardTab.setVisibility(bmsConnected ? View.VISIBLE : View.GONE);
        dashboardView.setVisibility(bmsConnected && page == 0 ? View.VISIBLE : View.GONE);
        connectionView.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        aboutView.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        Button[] tabs = {dashboardTab, connectionTab, aboutTab};
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == page;
            tabs[i].setTextColor(selected ? Color.WHITE : NAVY);
            tabs[i].setBackground(background(selected ? CYAN : Color.rgb(232, 240, 244), 11, 0));
        }
        if (mainScroll != null) mainScroll.smoothScrollTo(0, 0);
    }

    /** Requires complete BLE scan privileges; no physical location data is collected. */
    /** Stop every scheduled saved-BMS fallback or recovery on explicit user action. */
    private void stopAutomaticHandoff() {
        userConnectionOverride = true;
        autoDiscoveryActive = false;
        autoDirectAttempted = true;
        autoReconnectAddress = "";
        reconnectRetries = 0;
        autoConnectHandler.removeCallbacksAndMessages(null);
    }

    private void chooseDevice(String address) {
        stopAutomaticHandoff();
        requestedDeviceAddress = address;
        manualDisconnect = false;
        connecting = true;
    }

    /** Launch scan, then reconnect to the last successfully connected BLE device. */
    private void beginAutomaticDiscovery() {
        if (manualDisconnect || userConnectionOverride || connecting
                || ble == null || ble.isReady() || ble.isScanning()) return;
        autoReconnectAddress = getPreferences(0).getString("last_bms", "");
        autoDiscoveryActive = true;
        autoDirectAttempted = false;
        onLog(autoReconnectAddress.isEmpty()
                ? "Automatically scanning for nearby BMS devices"
                : "Automatically scanning for last BMS " + autoReconnectAddress);
        beginScan();
    }

    private void beginScan() {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            scanHint.setText("Grant Nearby devices and PRECISE Location. Android may otherwise return no BLE results.");
            status.setText("Bluetooth permissions needed to scan.");
            requestPermissions(missing.toArray(new String[0]), PERMISSIONS);
            return;
        }
        if (!ble.bluetoothEnabled()) {
            status.setText("Turn Bluetooth on to scan.");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), BLUETOOTH_ENABLE);
            return;
        }
        LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean locationOn = location != null && (Build.VERSION.SDK_INT >= 28
                ? location.isLocationEnabled()
                : (location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                   || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)));
        if (!locationOn) {
            status.setText("Turn on the phone's Location switch to scan.");
            new AlertDialog.Builder(this).setTitle("Turn on Location")
                    .setMessage("Some Android versions return no Bluetooth scan results when Location is off. The monitor does not read your GPS position.")
                    .setPositiveButton("Location settings", (d, w) -> startActivity(
                            new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        scan();
    }

    private void connectManual() {
        String address = manualMac.getText().toString().trim().toUpperCase(Locale.US);
        if (!address.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            manualMac.setError("Enter BLE address as XX:XX:XX:XX:XX:XX");
            return;
        }
        // Cancel saved-device recovery before requesting permissions or Bluetooth.
        // Those Android dialogs may take time, but the user's choice must persist.
        chooseDevice(address);
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingDirectConnection = true;
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT }, PERMISSIONS);
            return;
        }
        if (!ble.bluetoothEnabled()) {
            pendingDirectConnection = true;
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), BLUETOOTH_ENABLE);
            status.setText("Enable Bluetooth to connect.");
            return;
        }
        pendingDirectConnection = false;
        scanHint.setText("Connecting to selected BLE address…");
        onLog("Connecting directly to user-selected BLE address");
        ble.connectByAddress(address);
    }

    private void scan() {
        // Scanning discovers advertisements only; do not close the live GATT session.
        if (!ble.isReady()) connecting = false;
        visibleCount = 0;
        discovered.clear();
        deviceList.removeAllViews();
        scanButton.setText("Scanning…");
        scanButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        totalFound.setText("Looking for BLE devices…");
        scanHint.setText(autoDiscoveryActive && !autoReconnectAddress.isEmpty()
                ? "Scanning for the last connected BMS; it will reconnect automatically when discovered."
                : "Scanning nearby BLE devices without disconnecting the current BMS.");
        if (!ble.isReady()) status.setText("Looking for nearby Bluetooth devices…");
        onLog("Scan requested on Android API " + Build.VERSION.SDK_INT
                + ": Nearby devices / Location permissions granted, Bluetooth and Location ON");
        ble.scan();
        if (autoDiscoveryActive && !userConnectionOverride && !autoReconnectAddress.isEmpty()) {
            // Wait for actual advertisements (including legacy scan fallback) before
            // trying a direct connection. Limit startup delay if the BMS is hidden.
            autoConnectHandler.postDelayed(() -> {
                if (autoDiscoveryActive && !userConnectionOverride
                        && !connecting && ble.isScanning()) {
                    onLog("Last BMS not advertised after 12 seconds; trying saved address");
                    ble.stopScan(); // onScanFinished performs the direct fallback.
                }
            }, 12000);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 93) { refreshNotificationSwitch(); return; }
        if (code == PERMISSIONS && pendingDirectConnection) {
            if (Build.VERSION.SDK_INT < 31 ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                connectManual();
            else {
                pendingDirectConnection = false;
                status.setText("Nearby devices permission is needed to connect.");
            }
            return;
        }
        if (code == PERMISSIONS) {
            boolean granted = results.length == permissions.length && results.length > 0;
            for (int result : results) if (result != PackageManager.PERMISSION_GRANTED) granted = false;
            if (granted) beginScan();
            else {
                status.setText("Bluetooth scan permissions denied.");
                scanHint.setText("Grant Nearby devices and precise Location in Android App permissions.");
                scanButton.setEnabled(true);
                scanButton.setText("Scan for devices");
                progress.setVisibility(View.GONE);
            }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == BLUETOOTH_ENABLE) {
            if (res == RESULT_OK || ble.bluetoothEnabled()) {
                if (pendingDirectConnection) connectManual();
                else beginScan();
            }
            else status.setText("Bluetooth must be enabled to scan.");
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("check_updates_now", false) && updater != null) {
            intent.removeExtra("check_updates_now");
            showPage(2);
            updater.check(true);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        freshnessHandler.removeCallbacks(freshnessTicker);
        freshnessHandler.post(freshnessTicker);
        if (updater != null) updater.onResume();
        refreshNotificationSwitch();
        String lastError = getSharedPreferences("bms_updates", MODE_PRIVATE)
                .getString("last_install_error", null);
        if (lastError != null && !lastError.isEmpty()) {
            getSharedPreferences("bms_updates", MODE_PRIVATE).edit()
                    .remove("last_install_error").apply();
            showPage(2);
            if (firmwareHint != null) firmwareHint.setText("Last update failed. See details below.");
            new AlertDialog.Builder(this).setTitle("Update could not be installed")
                    .setMessage(lastError)
                    .setPositiveButton("OK", null).show();
        }
    }

    @Override protected void onPause() {
        freshnessHandler.removeCallbacks(freshnessTicker);
        super.onPause();
    }

    private static String ageLabel(long elapsed) {
        if (elapsed < 1000) return "just now";
        long seconds = elapsed / 1000;
        return seconds < 60 ? seconds + "s ago" : (seconds / 60) + "m ago";
    }

    /** A BLE link is not the same thing as receiving live telemetry packets. */
    private void updateLiveIndicator() {
        if (liveIndicator == null || !bmsConnected) return;
        long now = SystemClock.elapsedRealtime();
        if (lastBasicReadingMs < 0) {
            long wait = connectedSinceMs > 0 ? now - connectedSinceMs : 0;
            boolean delayed = wait >= 12000;
            liveIndicator.setText(delayed ? "NO TELEMETRY" : "WAITING FOR DATA");
            liveIndicator.setTextColor(delayed ? Color.rgb(174, 98, 0) : MUTED);
            updated.setText(delayed ? "Connected, but no valid battery readings yet" :
                    "Waiting for the first BMS response…");
        } else {
            long age = Math.max(0, now - lastBasicReadingMs);
            boolean live = age < 8000;
            liveIndicator.setText(live ? "● LIVE" : "● STALE");
            liveIndicator.setTextColor(live ? CYAN : Color.rgb(174, 98, 0));
            updated.setText((live ? telemetryCondition : "Readings are not updating") +
                    "  •  " + ageLabel(age));
        }
        if (detailsAge != null && bmsConnected) {
            if (lastDetailsReadingMs < 0) {
                detailsAge.setText("JBD / Xiaoxiang".equals(currentProtocol)
                        ? "Waiting for extended BMS data"
                        : "Gentai / ECO".equals(currentProtocol)
                        ? "Waiting for supported Gentai extended data" : "Status details not available");
                detailsAge.setTextColor(MUTED);
            } else {
                long age = Math.max(0, now - lastDetailsReadingMs);
                detailsAge.setText((age < 10000 ? "Extended readings " : "Extended readings stale · ")
                        + ageLabel(age));
                detailsAge.setTextColor(age < 10000 ? MUTED : Color.rgb(174, 98, 0));
            }
        }
        if (cellFreshness != null) {
            if (lastCellsReadingMs < 0) cellFreshness.setText("Awaiting cell telemetry");
            else {
                long age = Math.max(0, now - lastCellsReadingMs);
                cellFreshness.setText((age < 20000 ? "Cells updated " : "Cell data stale · ") +
                        ageLabel(age));
                cellFreshness.setTextColor(age < 20000 ? MUTED : Color.rgb(174, 98, 0));
            }
        }
    }

    @Override public void onStatus(String message, boolean connected) {
        runOnUiThread(() -> {
            if (connection == null) return;
            // A scan status is not proof of disconnection if a BMS is still ready.
            boolean ready = connected || (ble != null && ble.isReady());
            boolean wasConnected = bmsConnected;
            bmsConnected = ready;
            if (ready) {
                String address = ble.connectedAddress();
                // Merely scanning while the previous BMS is still connected is not
                // proof that a newly selected BMS connected successfully.
                boolean chosenDeviceReady = userConnectionOverride
                        && !requestedDeviceAddress.isEmpty()
                        && address != null
                        && address.equalsIgnoreCase(requestedDeviceAddress);
                if (!userConnectionOverride || chosenDeviceReady) {
                    connecting = false;
                    autoDiscoveryActive = false;
                    autoConnectHandler.removeCallbacksAndMessages(null);
                    reconnectRetries = 0;
                    manualDisconnect = false;
                    userConnectionOverride = false;
                    requestedDeviceAddress = "";
                    if (address != null && !address.isEmpty()) {
                        getPreferences(0).edit().putString("last_bms", address).apply();
                        if (manualMac != null) manualMac.setText(address);
                    }
                }
            } else if (!ble.isScanning() && (message.startsWith("Disconnected")
                    || message.startsWith("Could not connect")
                    || message.contains("timed out"))) {
                connecting = false;
            }
            connection.setText(ready ? "●  Connected to BMS" : "○  " + message);
            connection.setTextColor(ready ? CYAN : NAVY);
            if (!message.startsWith("Scanning")) status.setText(message);
            if (wasConnected && !ready) {
                // A dropped connection gets at most two recovery scans. Manual
                // disconnect never schedules reconnection, preventing retry loops.
                if (!manualDisconnect && !userConnectionOverride
                        && !connecting && reconnectRetries < 2) {
                    reconnectRetries++;
                    autoConnectHandler.removeCallbacks(reconnectTask);
                    autoConnectHandler.postDelayed(reconnectTask, 2500L * reconnectRetries);
                }
                currentProtocol = "Not identified";
                connectedDeviceLabel = "Unavailable";
                connectedSinceMs = -1;
                resetReadings();
                showPage(1);
            } else if (!wasConnected && ready) {
                connectedSinceMs = SystemClock.elapsedRealtime();
                resetReadings();
                setDetail(deviceName, "Device", connectedDeviceLabel);
                showPage(0);
                updateLiveIndicator();
            } else {
                dashboardTab.setVisibility(ready ? View.VISIBLE : View.GONE);
                if (!ready && currentPage == 0) showPage(1);
            }
        });
    }

    /** Prevent readings from a previously disconnected pack appearing as live data. */
    private void resetReadings() {
        lastBasicReadingMs = -1;
        lastCellsReadingMs = -1;
        lastDetailsReadingMs = -1;
        if (protocolLabel != null) protocolLabel.setText("Protocol: " + currentProtocol);
        refreshExtendedNotice();
        if (detailsAge != null) {
            detailsAge.setText("Additional fields appear when the protocol provides them.");
            detailsAge.setTextColor(MUTED);
        }
        setDetail(deviceName, "Device", connectedDeviceLabel);
        setDetail(protectionStatus, "Active protections", "Unavailable");
        if (protectionStatus != null) protectionStatus.setTextColor(NAVY);
        setDetail(mosCharge, "Charge MOSFET", "Unavailable");
        setDetail(mosDischarge, "Discharge MOSFET", "Unavailable");
        setDetail(balancingStatus, "Balancing", "Unavailable");
        setDetail(batteryTemperature, "Temperatures", "Unavailable");
        setDetail(cycleCount, "Cycle count", "Unavailable");
        setDetail(batteryHealth, "Battery health", "Unavailable");
        setDetail(reportedCells, "Reported cells", "Unavailable");
        setDetail(firmwareVersion, "BMS software", "Unavailable");
        setDetail(manufactureDate, "Manufacture date", "Unavailable");
        if (usageEstimate != null) usageEstimate.setText("Estimated runtime: —");
        if (cellSummary != null) cellSummary.setText("Cell minimum / maximum / average: unavailable");
        telemetryCondition = "Waiting for battery data";
        if (liveIndicator != null) {
            liveIndicator.setText("WAITING FOR DATA");
            liveIndicator.setTextColor(MUTED);
        }
        if (cellFreshness != null) {
            cellFreshness.setText("No cell readings yet");
            cellFreshness.setTextColor(MUTED);
        }
        soc.setText("-- %");
        voltage.setText("-- V");
        current.setText("-- A");
        power.setText("-- W");
        remaining.setText("-- Ah");
        capacity.setText("Rated capacity: -- Ah");
        updated.setText("Waiting for battery data");
        cellHeading.setText("Individual cell voltages");
        cellList.removeAllViews();
        cellList.addView(text("Awaiting a supported response from the BMS.", 12, MUTED, false));
    }

    @SuppressLint("MissingPermission")
    @Override public void onDevice(BluetoothDevice device, String name, int rssi, boolean likelyBms) {
        runOnUiThread(() -> {
            String address = device.getAddress();
            if (autoDiscoveryActive && !userConnectionOverride && !manualDisconnect && !ble.isReady()
                    && !connecting && address.equalsIgnoreCase(autoReconnectAddress)) {
                autoDiscoveryActive = false;
                connecting = true;
                scanHint.setText("Found last BMS. Reconnecting automatically…");
                onLog("Found last connected BMS advertisement; reconnecting");
                ble.connect(device);  // Retains the BLE address type observed in the scan.
                if (manualMac != null) manualMac.setText(address);
                scanButton.setEnabled(true);
                scanButton.setText("Scan for devices");
                progress.setVisibility(View.GONE);
                return;
            }
            if (!discovered.add(address)) return;
            if (visibleCount == 0) deviceList.removeAllViews();
            visibleCount++;
            totalFound.setText("Discovered: " + visibleCount + " BLE devices");
            if (visibleCount > 36 && !likelyBms) return;
            LinearLayout item = horizontal();
            item.setPadding(dp(8), dp(8), dp(8), dp(8));
            item.setBackground(background(
                    likelyBms ? Color.rgb(231, 248, 245) : Color.rgb(248, 250, 252),
                    10, BORDER));
            LinearLayout details = vertical();
            details.addView(text(name + (likelyBms ? "  •  BMS" : ""), 14, NAVY, likelyBms));
            details.addView(text(address + "  ·  " + rssi + " dBm", 11, MUTED, false), below(0, 3));
            LinearLayout.LayoutParams detailSize = new LinearLayout.LayoutParams(0, -2, 1);
            detailSize.rightMargin = dp(5);
            item.addView(details, detailSize);
            Button connect = button("Connect", likelyBms);
            connect.setOnClickListener(v -> {
                chooseDevice(address);
                ble.stopScan();
                deviceList.removeAllViews();
                deviceList.addView(text("Connecting to " + name + "…", 13, CYAN, true));
                if (manualMac != null) manualMac.setText(address);
                ble.connect(device);
                scanButton.setEnabled(true);
                scanButton.setText("Scan for devices");
                progress.setVisibility(View.GONE);
            });
            item.addView(connect, new LinearLayout.LayoutParams(dp(98), dp(45)));
            if (likelyBms) deviceList.addView(item, 0, below(0, 6));
            else deviceList.addView(item, below(0, 6));
            if (likelyBms) scanHint.setText("Possible BMS found. Tap Connect; the app will detect its telemetry protocol.");
        });
    }

    @Override public void onScanFinished() {
        runOnUiThread(() -> {
            if (scanButton == null) return;
            scanButton.setEnabled(true);
            scanButton.setText("Scan for devices");
            progress.setVisibility(View.GONE);
            if (connecting || ble.isReady() || userConnectionOverride) return;
            if (autoDiscoveryActive) {
                autoDiscoveryActive = false;
                if (!autoDirectAttempted && !autoReconnectAddress.isEmpty()) {
                    autoDirectAttempted = true;
                    manualDisconnect = false;
                    connecting = true;
                    scanHint.setText("Last BMS was not advertising; trying its saved BLE address…");
                    onLog("Last device not discovered. Trying direct BLE connection once.");
                    ble.connectByAddress(autoReconnectAddress);
                    return;
                }
            }
            if (visibleCount == 0 && !ble.isReady()) {
                scanHint.setText("No BLE devices reported after both scan methods. Verify Nearby devices and precise Location permissions, turn ON Location, or enter the BMS address and tap Direct.");
                status.setText("No Bluetooth LE advertisements received.");
            } else if (!ble.isReady() && deviceList.getChildCount() > 0) {
                scanHint.setText("Select a compatible battery device, or rescan closer to it.");
            }
        });
    }

    @Override public void onInfo(BatteryProtocol.BatteryInfo b) {
        runOnUiThread(() -> {
            if (!bmsConnected) return;
            lastBasicReadingMs = SystemClock.elapsedRealtime();
            soc.setText(String.format(Locale.US, "%.0f %%", b.socPercent));
            voltage.setText(String.format(Locale.US, "%.2f V", b.voltageV));
            current.setText(String.format(Locale.US, "%+.2f A", b.currentA));
            power.setText(String.format(Locale.US, "%+.1f W", b.powerW));
            remaining.setText(String.format(Locale.US, "%.2f Ah", b.remainingAh));
            capacity.setText(String.format(Locale.US, "BMS-reported capacity: %.1f Ah", b.totalAh));
            if (b.currentA < -0.1 && b.remainingAh > 0) {
                double hours = b.remainingAh / -b.currentA;
                usageEstimate.setText(hours <= 500 ?
                        String.format(Locale.US, "Estimated runtime at current load: %.1f h (approx.)", hours)
                        : "Estimated runtime: insufficient load to estimate");
            } else if (b.currentA > 0.1 && b.totalAh >= b.remainingAh) {
                double hours = (b.totalAh - b.remainingAh) / b.currentA;
                usageEstimate.setText(hours <= 500 ?
                        String.format(Locale.US, "Estimated charging time at current rate: %.1f h (approx.)", hours)
                        : "Estimated charging time: unavailable");
            } else usageEstimate.setText("Estimated runtime: — (battery idle)");
            telemetryCondition = b.currentA < -0.03 ? "Discharging" : b.currentA > 0.03 ? "Charging" : "Idle";
            updateLiveIndicator();
        });
    }

    @Override public void onProtocol(String protocol) {
        runOnUiThread(() -> {
            currentProtocol = protocol;
            if (protocolLabel != null) protocolLabel.setText("Protocol: " + protocol);
            refreshExtendedNotice();
        });
    }

    @Override public void onDeviceIdentity(String name) {
        runOnUiThread(() -> {
            if (name == null || name.trim().isEmpty()) return;
            connectedDeviceLabel = name.trim();
            if (bmsConnected) setDetail(deviceName, "Device", connectedDeviceLabel);
        });
    }

    @Override public void onDetails(JbdProtocol.Details b) {
        runOnUiThread(() -> {
            if (!bmsConnected) return;
            lastDetailsReadingMs = SystemClock.elapsedRealtime();
            String[] temps = new String[b.temperaturesC.length];
            for (int i = 0; i < temps.length; i++) {
                temps[i] = String.format(Locale.US, "T%d %.1f°C", i + 1, b.temperaturesC[i]);
            }
            setDetail(batteryTemperature, "Temperatures",
                    temps.length == 0 ? "No sensors reported" : android.text.TextUtils.join(" · ", temps));
            setDetail(protectionStatus, "Active protections", b.protectionSummary());
            protectionStatus.setTextColor(b.protectionFlags != 0 ? Color.rgb(174, 98, 0) : NAVY);
            setDetail(mosCharge, "Charge MOSFET", b.chargeMosOn ? "ON" : "OFF");
            setDetail(mosDischarge, "Discharge MOSFET", b.dischargeMosOn ? "ON" : "OFF");
            setDetail(balancingStatus, "Balancing", b.activeBalancingCells());
            setDetail(cycleCount, "Cycle count", Integer.toString(b.cycleCount));
            setDetail(reportedCells, "Reported cells", Integer.toString(b.cellCount));
            setDetail(firmwareVersion, "BMS software", b.softwareVersion);
            setDetail(manufactureDate, "Manufacture date", b.manufactureDate);
            updateLiveIndicator();
        });
    }

    @Override public void onGentaiDetails(BatteryProtocol.GentaiDetails b) {
        runOnUiThread(() -> {
            if (!bmsConnected || !"Gentai / ECO".equals(currentProtocol)) return;
            lastDetailsReadingMs = SystemClock.elapsedRealtime();
            java.util.ArrayList<String> temperatures = new java.util.ArrayList<>();
            for (int i = 0; i < b.possibleTemperaturesC.length; i++) {
                double value = b.possibleTemperaturesC[i];
                if (!Double.isNaN(value))
                    temperatures.add(String.format(Locale.US, "T%d %.0f°C", i + 1, value));
            }
            setDetail(batteryTemperature, "Possible temperatures",
                    temperatures.isEmpty() ? null : android.text.TextUtils.join(" · ", temperatures));
            setDetail(cycleCount, "Cycles (provisional)",
                    Integer.toString(b.possibleCycleCount));
            setDetail(batteryHealth, "Health (provisional)",
                    b.possibleHealthPercent >= 0 ? b.possibleHealthPercent + " %" : null);
            updateLiveIndicator();
        });
    }

    @Override public void onBatteryLevel(int percent) {
        runOnUiThread(() -> {
            if (!bmsConnected) return;
            lastBasicReadingMs = SystemClock.elapsedRealtime();
            soc.setText(percent + " %");
            telemetryCondition = "BLE Battery Service · level only";
            updateLiveIndicator();
        });
    }

    @Override public void onCells(double[] cells) {
        runOnUiThread(() -> {
            if (!bmsConnected) return;
            lastCellsReadingMs = SystemClock.elapsedRealtime();
            setDetail(reportedCells, "Cells measured", Integer.toString(cells.length));
            cellList.removeAllViews();
            double min = Double.MAX_VALUE, max = 0;
            for (double v : cells) { min = Math.min(min, v); max = Math.max(max, v); }
            cellHeading.setText(String.format(Locale.US, "%d cells  ·  spread %.0f mV", cells.length, (max - min) * 1000));
            double total = 0;
            int minIndex = 0, maxIndex = 0;
            for (int i = 0; i < cells.length; i++) {
                total += cells[i];
                if (cells[i] < cells[minIndex]) minIndex = i;
                if (cells[i] > cells[maxIndex]) maxIndex = i;
            }
            cellSummary.setText(String.format(Locale.US,
                    "Min: %.3f V (#%d)  ·  Max: %.3f V (#%d)  ·  Avg: %.3f V",
                    min, minIndex + 1, max, maxIndex + 1, total / cells.length));
            for (int i = 0; i < cells.length; i += 4) {
                LinearLayout row = horizontal();
                for (int k = i; k < Math.min(i + 4, cells.length); k++) {
                    TextView t = text(String.format(Locale.US, "%02d\n%.3f V", k+1, cells[k]), 12, NAVY, true);
                    t.setGravity(Gravity.CENTER);
                    t.setBackground(background(Color.rgb(235, 248, 250), 9, 0));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1);
                    lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                    row.addView(t, lp);
                }
                cellList.addView(row);
            }
            updateLiveIndicator();
        });
    }

    @Override public void onLog(String message) {
        runOnUiThread(() -> {
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            logs.insert(0, stamp + "  " + message + "\n");
            if (logs.length() > 12000) logs.setLength(12000);
            if (logText != null) logText.setText(logs.toString());
        });
    }

    @Override public void onMessage(String message) {
        runOnUiThread(() -> { if (firmwareHint != null) firmwareHint.setText(message); });
    }

    @Override public void onVersion(String version) {
        onLog("Available GitHub version: " + version);
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
    private LinearLayout panel() {
        LinearLayout card = vertical();
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(background(Color.WHITE, 15, BORDER));
        card.setElevation(dp(1));
        return card;
    }
    private LinearLayout metric(String name, String firstValue) {
        LinearLayout m = panel();
        m.addView(text(name, 11, MUTED, true));
        m.addView(text(firstValue, 22, NAVY, true), below(0, 8));
        return m;
    }
    private TextView text(String label, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : NAVY);
        b.setBackground(background(primary ? CYAN : Color.rgb(232, 240, 244), 11, 0));
        b.setPadding(dp(9), dp(4), dp(9), dp(4));
        b.setMinHeight(dp(44));
        return b;
    }
    private GradientDrawable background(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private LinearLayout.LayoutParams below(int width, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                width == 0 ? -1 : dp(width), -2);
        p.topMargin = dp(top); return p;
    }
    private LinearLayout.LayoutParams equalButton() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
        p.setMargins(dp(3), 0, dp(3), 0); return p;
    }
    private LinearLayout.LayoutParams equalCard() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.setMargins(dp(3), 0, dp(3), 0); return p;
    }
    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
    @Override protected void onDestroy() {
        autoConnectHandler.removeCallbacksAndMessages(null);
        freshnessHandler.removeCallbacks(freshnessTicker);
        if (ble != null) ble.close();
        super.onDestroy();
    }
}
