package com.tihloh.bmsmonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Foreground-only, read-only Gentai BLE monitor with clear scan diagnostics. */
public final class MainActivity extends Activity implements BleClient.Listener {
    private static final int PERMISSION_REQUEST = 50;
    private static final int ENABLE_BLUETOOTH_REQUEST = 51;
    private static final int NAVY = 0xFF112F46;
    private static final int INK = 0xFF183043;
    private static final int MUTED = 0xFF698296;
    private static final int TEAL = 0xFF007F8B;
    private static final int BG = 0xFFF3F7F9;
    private static final int STROKE = 0xFFDCE6EA;

    private BleClient ble;
    private UpdateManager updater;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ScanEntry> results = new LinkedHashMap<>();
    private final StringBuilder logBuffer = new StringBuilder();
    private LinearLayout root, discovered, cellsContainer, diagnosticDetails;
    private TextView scanStatus, connection, connectionDetail, headlineSoc, statusDetail;
    private TextView voltage, current, power, remaining, capacity, updated, cellsTitle, rawLog, deviceCount;
    private Button scanButton, moreButton, diagnosticsButton;
    private boolean scanning, showAll, autoConnectAttempted, diagnosticVisible;
    private boolean resumed;

    private static final class ScanEntry {
        final BluetoothDevice device;
        final String address, name;
        int rssi;
        boolean known;
        ScanEntry(BluetoothDevice device, String name, int rssi, boolean known) {
            this.device = device;
            this.name = name;
            this.address = device.getAddress();
            this.rssi = rssi;
            this.known = known;
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        prefs = getSharedPreferences("bms_preferences", MODE_PRIVATE);
        ble = new BleClient(this, this);
        updater = new UpdateManager(this);
        buildUi();
        onStatus("Ready to scan", false);
        onLog("v" + BuildConfig.VERSION_NAME + " • BLE monitor started");
        UpdateWorker.schedule(getApplicationContext());
        handler.postDelayed(() -> updater.check(false), 1200);
        handler.postDelayed(this::requestScan, 450);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        root = vertical();
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout header = vertical();
        header.setPadding(dp(18), dp(18), dp(18), dp(19));
        header.setBackground(rounded(NAVY, 20, NAVY));
        TextView small = text("BATTERY MONITOR", 11, 0xFF8DE4DF, true);
        small.setLetterSpacing(.14f);
        header.addView(small);
        header.addView(text("Gentai BMS", 26, Color.WHITE, true), margin(0, 6, 0, 0));
        header.addView(text("Local Bluetooth monitoring · v" + BuildConfig.VERSION_NAME, 12, 0xFFB6D1DA, false),
                margin(0, 3, 0, 0));
        LinearLayout hdrActions = horizontal();
        Button update = action("Check updates", false);
        update.setTextColor(INK);
        update.setOnClickListener(v -> updater.check(true));
        hdrActions.addView(update);
        header.addView(hdrActions, margin(0, 12, 0, 0));
        root.addView(header);

        LinearLayout connectionCard = card();
        connection = text("○  Disconnected", 16, INK, true);
        connectionCard.addView(connection);
        connectionDetail = text("Select your battery below to start reading.", 12, MUTED, false);
        connectionCard.addView(connectionDetail, margin(0, 4, 0, 0));
        root.addView(connectionCard, margin(0, 12, 0, 0));

        LinearLayout scannerCard = card();
        LinearLayout scannerRow = horizontal();
        TextView title = text("Nearby batteries", 16, INK, true);
        scannerRow.addView(title, new LinearLayout.LayoutParams(0, dp(30), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        deviceCount = text("0 found", 12, MUTED, true);
        deviceCount.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        scannerRow.addView(deviceCount);
        scannerCard.addView(scannerRow);

        scanStatus = text("Searching for ECO039E and compatible BLE devices.", 12, MUTED, false);
        scannerCard.addView(scanStatus, margin(0, 5, 0, 9));
        scanButton = action("Scan again", true);
        scanButton.setOnClickListener(v -> requestScan());
        scannerCard.addView(scanButton, margin(0, 0, 0, 4));
        discovered = vertical();
        scannerCard.addView(discovered, margin(0, 7, 0, 0));
        moreButton = action("Show all Bluetooth devices", false);
        moreButton.setOnClickListener(v -> {
            showAll = !showAll;
            moreButton.setText(showAll ? "Show likely batteries only" : "Show all Bluetooth devices");
            rebuildDevices();
        });
        scannerCard.addView(moreButton, margin(0, 6, 0, 0));
        root.addView(scannerCard, margin(0, 12, 0, 0));

        LinearLayout socCard = card();
        socCard.addView(text("STATE OF CHARGE", 11, MUTED, true));
        headlineSoc = text("--%", 49, TEAL, true);
        socCard.addView(headlineSoc, margin(0, 2, 0, 0));
        statusDetail = text("Waiting for battery data", 13, MUTED, false);
        socCard.addView(statusDetail);
        root.addView(socCard, margin(0, 12, 0, 0));

        LinearLayout metrics = vertical();
        LinearLayout row1 = horizontal();
        voltage = metric(row1, "VOLTAGE", "-- V");
        current = metric(row1, "CURRENT", "-- A");
        metrics.addView(row1);
        LinearLayout row2 = horizontal();
        power = metric(row2, "POWER", "-- W");
        remaining = metric(row2, "REMAINING", "-- Ah");
        metrics.addView(row2, margin(0, 8, 0, 0));
        root.addView(metrics, margin(0, 10, 0, 0));

        LinearLayout capacityCard = card();
        capacity = text("Battery capacity: -- Ah", 14, INK, true);
        updated = text("Last updated: waiting", 12, MUTED, false);
        capacityCard.addView(capacity);
        capacityCard.addView(updated, margin(0, 6, 0, 0));
        root.addView(capacityCard, margin(0, 10, 0, 0));

        LinearLayout cellsCard = card();
        cellsTitle = text("Cell voltages", 15, INK, true);
        cellsCard.addView(cellsTitle);
        cellsContainer = vertical();
        cellsContainer.addView(text("Cell readings will appear when supported by the BMS.", 12, MUTED, false));
        cellsCard.addView(cellsContainer, margin(0, 8, 0, 0));
        root.addView(cellsCard, margin(0, 10, 0, 0));

        LinearLayout diagnosticsCard = card();
        diagnosticsButton = action("Show Bluetooth diagnostics", false);
        diagnosticsButton.setOnClickListener(v -> {
            diagnosticVisible = !diagnosticVisible;
            diagnosticDetails.setVisibility(diagnosticVisible ? View.VISIBLE : View.GONE);
            diagnosticsButton.setText(diagnosticVisible ? "Hide Bluetooth diagnostics" : "Show Bluetooth diagnostics");
        });
        diagnosticsCard.addView(diagnosticsButton);
        diagnosticDetails = vertical();
        diagnosticDetails.setVisibility(View.GONE);
        diagnosticsCard.addView(diagnosticDetails);
        Button copy = action("Copy log", false);
        copy.setOnClickListener(v -> {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText("BMS BLE log", logBuffer.toString()));
            Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show();
        });
        diagnosticDetails.addView(copy, margin(0, 7, 0, 0));
        rawLog = text("", 11, MUTED, false);
        rawLog.setTypeface(Typeface.MONOSPACE);
        rawLog.setTextIsSelectable(true);
        rawLog.setMaxLines(18);
        diagnosticDetails.addView(rawLog, margin(0, 6, 0, 0));
        root.addView(diagnosticsCard, margin(0, 10, 0, 0));

        root.addView(text("Experimental telemetry only. Do not use this app for battery protection or load cutoff.",
                11, MUTED, false), margin(0, 13, 0, 0));
    }

    private TextView metric(LinearLayout row, String label, String value) {
        LinearLayout c = card();
        c.setPadding(dp(12), dp(12), dp(12), dp(13));
        c.addView(text(label, 10, MUTED, true));
        TextView output = text(value, 20, INK, true);
        c.addView(output, margin(0, 7, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(c, lp);
        return output;
    }

    private void requestScan() {
        if (isFinishing()) return;
        String[] permissions = Build.VERSION.SDK_INT >= 31
                ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                scanStatus.setText("Grant Nearby devices permission to find your Gentai BMS.");
                requestPermissions(permissions, PERMISSION_REQUEST);
                return;
            }
        }
        if (!ble.bluetoothEnabled()) {
            scanStatus.setText("Enable Bluetooth, then we'll scan automatically.");
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    ENABLE_BLUETOOTH_REQUEST); }
            catch (Exception e) { scanStatus.setText("Please enable Bluetooth in Android Settings."); }
            return;
        }
        if (Build.VERSION.SDK_INT < 31) {
            LocationManager loc = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (loc != null && !loc.isLocationEnabled()) {
                scanStatus.setText("Enable Android Location for BLE scanning on this Android version.");
                new AlertDialog.Builder(this).setTitle("Location required for Bluetooth scanning")
                        .setMessage("Android 11 and earlier requires Location to be enabled for scanning.")
                        .setPositiveButton("Open settings", (d, w) -> startActivity(
                                new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton("Cancel", null).show();
                return;
            }
        }
        beginScan();
    }

    private void beginScan() {
        scanning = true;
        autoConnectAttempted = false;
        results.clear();
        scanButton.setText("Scanning…");
        scanStatus.setText("Scanning for 20 seconds. Keep the Gentai app closed.");
        rebuildDevices();
        ble.scan();
    }

    @Override public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != PERMISSION_REQUEST) return;
        boolean allowed = grants.length == permissions.length;
        for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) allowed = false;
        if (allowed) requestScan();
        else {
            scanStatus.setText("Bluetooth permission denied. Enable Nearby devices in Android app settings.");
            scanButton.setText("Try scanning again");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BLUETOOTH_REQUEST && ble.bluetoothEnabled()) requestScan();
    }

    @SuppressLint("MissingPermission")
    @Override public void onDevice(BluetoothDevice device, String name, int rssi, boolean likelyBms) {
        String key = device.getAddress();
        ScanEntry previous = results.get(key);
        if (previous == null) results.put(key, new ScanEntry(device, name, rssi, likelyBms));
        else {
            previous.rssi = rssi;
            previous.known = previous.known || likelyBms;
        }
        String saved = prefs.getString("last_bms_mac", "");
        if (!autoConnectAttempted && !saved.isEmpty() && saved.equals(key) && likelyBms) {
            autoConnectAttempted = true;
            scanning = false;
            scanStatus.setText("Reconnecting to your saved Gentai BMS…");
            ble.connect(device);
        }
        rebuildDevices();
    }

    private void rebuildDevices() {
        if (discovered == null) return;
        discovered.removeAllViews();
        int compatible = 0;
        for (ScanEntry entry : results.values()) if (entry.known) compatible++;
        deviceCount.setText(compatible + " likely BMS" + (compatible == 1 ? "" : " devices"));

        List<ScanEntry> sorted = new ArrayList<>(results.values());
        sorted.sort((a, b) -> {
            if (a.known != b.known) return a.known ? -1 : 1;
            return Integer.compare(b.rssi, a.rssi);
        });
        int shown = 0;
        for (ScanEntry entry : sorted) {
            if (!entry.known && !showAll) continue;
            shown++;
            LinearLayout container = vertical();
            container.setPadding(dp(10), dp(10), dp(10), dp(8));
            container.setBackground(rounded(entry.known ? 0xFFEAF6F5 : 0xFFF4F6F7,
                    12, entry.known ? 0xFFAFDFD9 : STROKE));
            LinearLayout line = horizontal();
            TextView name = text((entry.known ? "●  " : "○  ") + entry.name,
                    14, INK, true);
            line.addView(name, new LinearLayout.LayoutParams(0, dp(28), 1));
            name.setGravity(Gravity.CENTER_VERTICAL);
            TextView signal = text(entry.rssi + " dBm", 11, MUTED, false);
            line.addView(signal);
            container.addView(line);
            container.addView(text(entry.known
                    ? "Likely compatible battery • Tap to connect"
                    : "Other BLE device • Tap to inspect", 11, MUTED, false));
            container.setOnClickListener(v -> selectDevice(entry));
            discovered.addView(container, margin(0, 5, 0, 0));
        }
        if (shown == 0) {
            discovered.addView(text(scanning
                    ? "Listening for Bluetooth advertisements…"
                    : "No compatible BMS found. Try Show all Bluetooth devices or move closer.",
                    12, MUTED, false), margin(0, 6, 0, 0));
        }
    }

    private void selectDevice(ScanEntry entry) {
        if (!entry.known) {
            new AlertDialog.Builder(this).setTitle("Unknown Bluetooth device")
                .setMessage(entry.name + " has not been identified as a compatible BMS. Try connecting anyway?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (d, w) -> doConnect(entry)).show();
        } else doConnect(entry);
    }

    private void doConnect(ScanEntry entry) {
        scanning = false;
        scanButton.setText("Scan again");
        scanStatus.setText("Connecting to " + entry.name + "…");
        prefs.edit().putString("last_bms_mac", entry.address).apply();
        ble.connect(entry.device);
    }

    @Override public void onScanFinished() {
        if (!scanning) return;
        scanning = false;
        scanButton.setText("Scan again");
        if (results.isEmpty()) {
            scanStatus.setText("No BLE devices detected. Check Bluetooth and Nearby devices permissions.");
        } else {
            scanStatus.setText("Scan complete. " + results.size() +
                    " Bluetooth devices detected. Select a battery below.");
        }
        rebuildDevices();
    }

    @Override public void onStatus(String text, boolean ready) {
        runOnUiThread(() -> {
            if (connection == null) return;
            connection.setText((ready ? "●  " : "○  ") + (ready ? "Connected" : text));
            connection.setTextColor(ready ? TEAL : INK);
            connectionDetail.setText(ready ? "Live telemetry · BLE connected" : text);
            if (!ready && text.toLowerCase(Locale.US).contains("error")) {
                scanStatus.setText(text);
            }
        });
    }

    @Override public void onInfo(BatteryProtocol.BatteryInfo info) {
        runOnUiThread(() -> {
            headlineSoc.setText(String.format(Locale.US, "%.0f%%", info.socPercent));
            voltage.setText(String.format(Locale.US, "%.2f V", info.voltageV));
            current.setText(String.format(Locale.US, "%+.2f A", info.currentA));
            power.setText(String.format(Locale.US, "%+.1f W", info.powerW));
            remaining.setText(String.format(Locale.US, "%.2f Ah", info.remainingAh));
            capacity.setText(String.format(Locale.US, "Rated capacity: %.1f Ah", info.totalAh));
            statusDetail.setText(info.currentA < -0.03 ? "Discharging" :
                    info.currentA > 0.03 ? "Charging" : "Idle");
            updated.setText("Last updated: " +
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        });
    }

    @Override public void onCells(double[] cells) {
        runOnUiThread(() -> {
            cellsContainer.removeAllViews();
            if (cells.length == 0) return;
            double min = Double.MAX_VALUE, max = 0;
            for (double v : cells) { min = Math.min(min, v); max = Math.max(max, v); }
            cellsTitle.setText(String.format(Locale.US, "%d cells · Δ %.0f mV",
                    cells.length, (max - min) * 1000));
            for (int start = 0; start < cells.length; start += 4) {
                LinearLayout row = horizontal();
                for (int i = start; i < Math.min(start + 4, cells.length); i++) {
                    TextView item = text(String.format(Locale.US, "%02d\n%.3f V", i + 1, cells[i]),
                            12, INK, true);
                    item.setGravity(Gravity.CENTER);
                    item.setBackground(rounded(0xFFEDF7F9, 8, 0xFFDAECEF));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1);
                    lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                    row.addView(item, lp);
                }
                cellsContainer.addView(row);
            }
        });
    }

    @Override public void onLog(String event) {
        runOnUiThread(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss",
                    Locale.getDefault()).format(new Date());
            logBuffer.insert(0, timestamp + "  " + event + "\n");
            if (logBuffer.length() > 12000) logBuffer.setLength(12000);
            if (rawLog != null) rawLog.setText(logBuffer.toString());
        });
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (ble != null) ble.close();
        super.onDestroy();
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout horizontal() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout card() {
        LinearLayout view = vertical();
        view.setPadding(dp(15), dp(13), dp(15), dp(14));
        view.setBackground(rounded(Color.WHITE, 15, STROKE));
        return view;
    }
    private Button action(String title, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(13);
        button.setTextColor(primary ? Color.WHITE : TEAL);
        button.setBackground(rounded(primary ? TEAL : 0xFFE7F3F4, 10,
                primary ? TEAL : 0xFFC5E1E4));
        button.setMinHeight(dp(40));
        button.setPadding(dp(13), dp(7), dp(13), dp(7));
        return button;
    }
    private TextView text(String value, int size, int color, boolean strong) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (strong) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private GradientDrawable rounded(int bg, int radius, int stroke) {
        GradientDrawable view = new GradientDrawable();
        view.setColor(bg);
        view.setCornerRadius(dp(radius));
        view.setStroke(dp(1), stroke);
        return view;
    }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }
    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
