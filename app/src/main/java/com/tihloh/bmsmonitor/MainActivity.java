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
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private TextView connection, scanHint, firmwareHint, soc, status, voltage, current, power,
            remaining, capacity, updated, cellHeading, logText, totalFound;
    private Button scanButton, disconnectButton;
    private EditText manualMac;
    private ProgressBar progress;
    private final StringBuilder logs = new StringBuilder();
    private boolean pendingAutoConnect = true;
    private boolean pendingDirectConnection;
    private boolean connecting;
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
        updater.check(fromNotification);
        if (fromNotification) getIntent().removeExtra("check_updates_now");
        // Request permission on first use only when the user taps Scan.
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

        TextView title = text("BMS Monitor", 25, NAVY, true);
        root.addView(title);
        TextView subtitle = text("GENTAI  /  LIVE BATTERY TELEMETRY", 11, CYAN, true);
        subtitle.setLetterSpacing(0.06f);
        root.addView(subtitle, below(0, 5));

        LinearLayout connectionCard = panel();
        LinearLayout topLine = horizontal();
        connection = text("Not connected", 16, NAVY, true);
        topLine.addView(connection, new LinearLayout.LayoutParams(0, dp(30), 1));
        connection.setGravity(Gravity.CENTER_VERTICAL);
        TextView version = text("v" + BuildConfig.VERSION_NAME, 11, MUTED, true);
        version.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topLine.addView(version);
        connectionCard.addView(topLine);
        status = text("Connect to ECO039E to start reading.", 13, MUTED, false);
        connectionCard.addView(status, below(0, 5));
        root.addView(connectionCard, below(0, 15));

        Button gentaiConnect = button("Connect directly to ECO039E", true);
        gentaiConnect.setTextSize(15);
        gentaiConnect.setOnClickListener(v -> connectManual());
        root.addView(gentaiConnect, below(0, 11));
        LinearLayout actions = horizontal();
        scanButton = button("Scan for devices", true);
        scanButton.setOnClickListener(v -> beginScan());
        disconnectButton = button("Disconnect", false);
        disconnectButton.setOnClickListener(v -> {
            ble.disconnect();
            onStatus("Disconnected", false);
            status.setText("Tap Scan / Connect to reconnect.");
        });
        actions.addView(scanButton, equalButton());
        actions.addView(disconnectButton, equalButton());
        root.addView(actions, below(0, 10));

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
        manualMac.setText(getPreferences(0).getString("last_bms", "0B:23:EB:D7:03:9E"));
        manualMac.setSelectAllOnFocus(true);
        manualMac.setPadding(dp(9), 0, dp(9), 0);
        manualMac.setBackground(background(Color.rgb(232, 240, 244), 10, 0));
        LinearLayout.LayoutParams edit = new LinearLayout.LayoutParams(0, dp(44), 1);
        edit.rightMargin = dp(7);
        directRow.addView(manualMac, edit);
        Button manualConnect = button("Direct", true);
        manualConnect.setOnClickListener(v -> connectManual());
        directRow.addView(manualConnect, new LinearLayout.LayoutParams(dp(98), dp(44)));
        scanCard.addView(text("DIRECT BLE CONNECTION · Bypass scanning with the BMS address shown by nRF Connect:", 12, MUTED, true), below(0, 10));
        scanCard.addView(directRow, below(0, 5));
        root.addView(scanCard, below(0, 10));

        LinearLayout socPanel = panel();
        socPanel.addView(text("STATE OF CHARGE", 11, MUTED, true));
        soc = text("-- %", 44, CYAN, true);
        socPanel.addView(soc, below(0, 5));
        updated = text("No readings received yet", 12, MUTED, false);
        socPanel.addView(updated);
        root.addView(socPanel, below(0, 15));

        LinearLayout metrics1 = horizontal();
        LinearLayout voltsCard = metric("TOTAL VOLTAGE", "-- V"); voltage = (TextView) voltsCard.getChildAt(1);
        LinearLayout a = metric("CURRENT", "-- A"); current = (TextView) a.getChildAt(1);
        metrics1.addView(voltsCard, equalCard()); metrics1.addView(a, equalCard());
        root.addView(metrics1, below(0, 9));

        LinearLayout metrics2 = horizontal();
        LinearLayout w = metric("POWER", "-- W"); power = (TextView) w.getChildAt(1);
        LinearLayout ah = metric("REMAINING", "-- Ah"); remaining = (TextView) ah.getChildAt(1);
        metrics2.addView(w, equalCard()); metrics2.addView(ah, equalCard());
        root.addView(metrics2, below(0, 9));

        LinearLayout more = panel();
        capacity = text("Rated capacity: -- Ah", 14, NAVY, true);
        more.addView(capacity);
        root.addView(more, below(0, 9));

        LinearLayout cells = panel();
        cellHeading = text("Individual cell voltages", 15, NAVY, true);
        cells.addView(cellHeading);
        cellList = vertical();
        cellList.addView(text("Awaiting a supported response from the BMS.", 12, MUTED, false));
        cells.addView(cellList, below(0, 9));
        root.addView(cells, below(0, 10));

        LinearLayout updatePanel = panel();
        updatePanel.addView(text("App updates", 15, NAVY, true));
        firmwareHint = text("Checks on launch and approximately hourly in the background. Android confirms installation.", 12, MUTED, false);
        updatePanel.addView(firmwareHint, below(0, 5));
        Button check = button("Check for updates", false);
        check.setOnClickListener(v -> updater.check(true));
        updatePanel.addView(check, below(0, 9));
        if (Build.VERSION.SDK_INT >= 33) {
            Button alerts = button("Enable update notifications", false);
            alerts.setOnClickListener(v -> {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 93);
                } else {
                    Toast.makeText(this, "Update notifications already enabled", Toast.LENGTH_SHORT).show();
                }
            });
            updatePanel.addView(alerts, below(0, 6));
        }
        root.addView(updatePanel, below(0, 10));

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
        root.addView(logBox, below(0, 10));

        TextView footer = text("Experimental read-only monitor. Not a battery protection system.", 11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, below(0, 15));
    }

    /** Requires complete BLE scan privileges; no physical location data is collected. */
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
            status.setText("Permission needed to discover the Gentai BMS.");
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
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingDirectConnection = true;
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT }, PERMISSIONS);
            return;
        }
        if (!ble.bluetoothEnabled()) {
            pendingDirectConnection = true;
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), BLUETOOTH_ENABLE);
            status.setText("Enable Bluetooth to connect to ECO039E.");
            return;
        }
        pendingDirectConnection = false;
        getPreferences(0).edit().putString("last_bms", address).apply();
        connecting = true;
        scanHint.setText("Connecting directly without scanning…");
        onLog("Connecting directly to saved BLE address");
        ble.connectByAddress(address);
    }

    private void scan() {
        connecting = false;
        visibleCount = 0;
        discovered.clear();
        deviceList.removeAllViews();
        scanButton.setText("Scanning…");
        scanButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        totalFound.setText("Looking for Gentai BMS…");
        scanHint.setText("Scanning all Bluetooth LE devices for up to 20 seconds; if no results after 8 seconds, a compatibility scan will run.");
        status.setText("Looking for nearby Bluetooth devices…");
        onLog("Scan requested on Android API " + Build.VERSION.SDK_INT
                + ": Nearby devices / Location permissions granted, Bluetooth and Location ON");
        ble.scan();
        if (mainScroll != null && scanCard != null) mainScroll.post(() ->
                mainScroll.smoothScrollTo(0, scanCard.getTop()));
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
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
            else status.setText("Bluetooth must be enabled to detect ECO039E.");
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("check_updates_now", false) && updater != null) {
            intent.removeExtra("check_updates_now");
            updater.check(true);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (updater != null) updater.onResume();
    }

    @Override public void onStatus(String message, boolean connected) {
        runOnUiThread(() -> {
            if (connection == null) return;
            connection.setText(connected ? "●  Connected to BMS" : "○  " + message);
            connection.setTextColor(connected ? CYAN : NAVY);
            if (!message.startsWith("Scanning")) status.setText(message);
        });
    }

    @SuppressLint("MissingPermission")
    @Override public void onDevice(BluetoothDevice device, String name, int rssi, boolean likelyBms) {
        runOnUiThread(() -> {
            String address = device.getAddress();
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
                connecting = true;
                ble.stopScan();
                deviceList.removeAllViews();
                deviceList.addView(text("Connecting to " + name + "…", 13, CYAN, true));
                getPreferences(0).edit().putString("last_bms", address).apply();
                if (manualMac != null) manualMac.setText(address);
                ble.connect(device);
                scanButton.setEnabled(true);
                scanButton.setText("Scan for devices");
                progress.setVisibility(View.GONE);
            });
            item.addView(connect, new LinearLayout.LayoutParams(dp(98), dp(45)));
            if (likelyBms) deviceList.addView(item, 0, below(0, 6));
            else deviceList.addView(item, below(0, 6));
            if (likelyBms) scanHint.setText("Likely Gentai BMS found. Tap Connect beside the device.");
        });
    }

    @Override public void onScanFinished() {
        runOnUiThread(() -> {
            if (scanButton == null) return;
            scanButton.setEnabled(true);
            scanButton.setText("Scan for devices");
            progress.setVisibility(View.GONE);
            if (connecting) return;
            if (visibleCount == 0 && !ble.isReady()) {
                scanHint.setText("No BLE devices reported after both scan methods. Verify Nearby devices and precise Location permissions, turn ON Location, or enter the BMS address and tap Direct.");
                status.setText("No Bluetooth LE advertisements received.");
            } else if (!ble.isReady() && deviceList.getChildCount() > 0) {
                scanHint.setText("Select ECO039E or BMC1_039E from the results. If missing, rescan closer to the battery.");
            }
        });
    }

    @Override public void onInfo(BatteryProtocol.BatteryInfo b) {
        runOnUiThread(() -> {
            soc.setText(String.format(Locale.US, "%.0f %%", b.socPercent));
            voltage.setText(String.format(Locale.US, "%.2f V", b.voltageV));
            current.setText(String.format(Locale.US, "%+.2f A", b.currentA));
            power.setText(String.format(Locale.US, "%+.1f W", b.powerW));
            remaining.setText(String.format(Locale.US, "%.2f Ah", b.remainingAh));
            capacity.setText(String.format(Locale.US, "Rated capacity: %.1f Ah", b.totalAh));
            String condition = b.currentA < -0.03 ? "Discharging" : b.currentA > 0.03 ? "Charging" : "Idle";
            updated.setText(condition + "  •  Updated " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        });
    }

    @Override public void onCells(double[] cells) {
        runOnUiThread(() -> {
            cellList.removeAllViews();
            double min = Double.MAX_VALUE, max = 0;
            for (double v : cells) { min = Math.min(min, v); max = Math.max(max, v); }
            cellHeading.setText(String.format(Locale.US, "%d cells  ·  spread %.0f mV", cells.length, (max - min) * 1000));
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
        if (ble != null) ble.close();
        super.onDestroy();
    }
}
