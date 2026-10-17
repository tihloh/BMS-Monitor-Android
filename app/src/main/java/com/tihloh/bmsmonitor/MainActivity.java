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
    private LinearLayout deviceList, cellList, logBox;
    private TextView connection, scanHint, firmwareHint, soc, status, voltage, current, power,
            remaining, capacity, updated, cellHeading, logText, totalFound;
    private Button scanButton, disconnectButton;
    private EditText manualMac;
    private ProgressBar progress;
    private final StringBuilder logs = new StringBuilder();
    private boolean pendingAutoConnect = true;
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

        LinearLayout actions = horizontal();
        scanButton = button("Scan / Connect", true);
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

        LinearLayout scanCard = panel();
        LinearLayout scanRow = horizontal();
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        progressLp.rightMargin = dp(8);
        scanRow.addView(progress, progressLp);
        totalFound = text("Nearby BMS devices", 14, NAVY, true);
        scanRow.addView(totalFound);
        scanCard.addView(scanRow);
        scanHint = text("Tap Scan / Connect. Gentai and nRF Connect must be disconnected.", 12, MUTED, false);
        scanCard.addView(scanHint, below(0, 7));
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
        Button manualConnect = button("Connect", false);
        manualConnect.setOnClickListener(v -> connectManual());
        directRow.addView(manualConnect, new LinearLayout.LayoutParams(dp(98), dp(44)));
        scanCard.addView(text("If the scan doesn't find ECO039E, enter its BLE address from nRF Connect:", 12, MUTED, false), below(0, 10));
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

    private void beginScan() {
        if (Build.VERSION.SDK_INT >= 31) {
            String[] ps = {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
            if (checkSelfPermission(ps[0]) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(ps[1]) != PackageManager.PERMISSION_GRANTED) {
                status.setText("Android requires Nearby Devices permission before scanning.");
                requestPermissions(ps, PERMISSIONS);
                return;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                status.setText("Grant Location permission to discover nearby BLE devices.");
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS);
                return;
            }
            LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (location != null && !location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                new AlertDialog.Builder(this).setTitle("Enable Location")
                        .setMessage("On Android 11 and earlier, the system requires Location services enabled for BLE scanning.")
                        .setPositiveButton("Settings", (d,w) -> startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton("Cancel", null).show();
                return;
            }
        }
        if (!ble.bluetoothEnabled()) {
            status.setText("Turn Bluetooth on to scan.");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), BLUETOOTH_ENABLE);
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
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT }, PERMISSIONS);
            return;
        }
        if (!ble.bluetoothEnabled()) {
            status.setText("Turn Bluetooth on and try connecting again.");
            return;
        }
        getPreferences(0).edit().putString("last_bms", address).apply();
        ble.connectByAddress(address);
    }

    private void scan() {
        visibleCount = 0;
        discovered.clear();
        deviceList.removeAllViews();
        scanButton.setText("Scanning…");
        scanButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        totalFound.setText("Looking for Gentai BMS…");
        scanHint.setText("Scan takes about 16 seconds. Tap ECO039E when it appears.");
        status.setText("Looking for nearby Bluetooth devices…");
        ble.scan();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == PERMISSIONS) {
            boolean granted = results.length == permissions.length && results.length > 0;
            for (int result : results) if (result != PackageManager.PERMISSION_GRANTED) granted = false;
            if (granted) beginScan();
            else {
                status.setText("Nearby Devices permission denied. Enable it in Android App Settings.");
                scanHint.setText("Settings → Apps → BMS Monitor → Permissions → Nearby devices.");
            }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == BLUETOOTH_ENABLE) {
            if (res == RESULT_OK || ble.bluetoothEnabled()) beginScan();
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
            if (!discovered.add(device.getAddress())) return;
            if (visibleCount == 0) deviceList.removeAllViews();
            visibleCount++;
            totalFound.setText("Devices found: " + visibleCount);
            String display = name + (likelyBms ? "  •  BMS" : "") + "  (" + rssi + " dBm)";
            Button choose = button(display, likelyBms);
            choose.setAllCaps(false);
            choose.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            choose.setOnClickListener(v -> {
                ble.stopScan();
                deviceList.removeAllViews();
                deviceList.addView(text("Connecting to " + name + "…", 13, CYAN, true));
                getPreferences(0).edit().putString("last_bms", device.getAddress()).apply();
                if (manualMac != null) manualMac.setText(device.getAddress());
                ble.connect(device);
                scanButton.setEnabled(true);
                scanButton.setText("Scan / Connect");
            });
            if (likelyBms) deviceList.addView(choose, 0, below(0, 6));
            else deviceList.addView(choose, below(0, 6));
            if (likelyBms) scanHint.setText("Gentai-compatible advertisement found. Tap its name to connect.");
        });
    }

    @Override public void onScanFinished() {
        runOnUiThread(() -> {
            if (scanButton == null) return;
            scanButton.setEnabled(true);
            scanButton.setText("Scan / Connect");
            progress.setVisibility(View.GONE);
            if (visibleCount == 0 && !ble.isReady()) {
                scanHint.setText("No devices discovered. Move closer, turn Bluetooth off/on, close the Gentai app, then rescan.");
                status.setText("No BLE devices found — see troubleshooting instructions below.");
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
