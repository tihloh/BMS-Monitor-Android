package com.tihloh.bmsmonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
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
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Simple light, compact Android BMS telemetry dashboard and BLE device picker. */
public final class MainActivity extends Activity implements BleClient.Listener {
    private static final int REQUEST_BLE = 40;
    private static final int NAVY = Color.rgb(23, 43, 63);
    private static final int BLUE = Color.rgb(12, 119, 141);
    private static final int MUTED = Color.rgb(106, 121, 133);
    private static final int BACKGROUND = Color.rgb(246, 248, 251);
    private static final int LIGHT = Color.rgb(231, 241, 245);
    private static final int GREEN = Color.rgb(23, 134, 107);
    private final Set<String> scanned = new HashSet<>();
    private final StringBuilder history = new StringBuilder();

    private BleClient ble;
    private UpdateClient updates;
    private LinearLayout devices;
    private LinearLayout cellPanel;
    private TextView status;
    private TextView scanHint;
    private TextView soc;
    private TextView direction;
    private TextView voltage;
    private TextView current;
    private TextView power;
    private TextView remaining;
    private TextView capacity;
    private TextView sampleTime;
    private TextView cellTitle;
    private TextView log;
    private TextView updaterStatus;
    private TextView knownButton;
    private EditText manualMac;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        ble = new BleClient(this, this);
        updates = new UpdateClient(this);
        buildScreen();
        onStatus("Not connected", false);
        onLog("Ready. This experimental monitor sends telemetry requests only.");
        updates.check(false, this::showUpdateMessage);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout body = stack();
        body.setPadding(dp(14), dp(15), dp(14), dp(25));
        scroll.addView(body);
        setContentView(scroll);

        LinearLayout header = stack();
        header.setPadding(dp(16), dp(15), dp(16), dp(15));
        header.setBackground(shape(NAVY, 17));
        TextView h = text("BMS Monitor", 23, Color.WHITE, true);
        header.addView(h);
        header.addView(text("GENTAI  ·  LIVE BLUETOOTH TELEMETRY", 11, Color.rgb(181, 208, 216), true), margin(0, 4));
        status = text("Not connected", 13, Color.WHITE, true);
        header.addView(status, margin(0, 10));
        body.addView(header);

        LinearLayout summary = card();
        summary.addView(text("STATE OF CHARGE", 11, MUTED, true));
        soc = text("--%", 43, BLUE, true);
        summary.addView(soc, margin(0, 3));
        direction = text("Connect to ECO039E to read battery data", 13, MUTED, false);
        summary.addView(direction);
        body.addView(summary, margin(0, 10));

        LinearLayout row1 = horizontal();
        LinearLayout volts = metric("BATTERY VOLTAGE"); voltage = text("-- V", 22, NAVY, true); volts.addView(voltage, margin(0, 8));
        LinearLayout amps = metric("CURRENT"); current = text("-- A", 22, NAVY, true); amps.addView(current, margin(0, 8));
        row1.addView(volts, weighted());
        row1.addView(amps, weighted());
        body.addView(row1, margin(0, 9));

        LinearLayout row2 = horizontal();
        LinearLayout watts = metric("POWER"); power = text("-- W", 21, NAVY, true); watts.addView(power, margin(0, 8));
        LinearLayout ah = metric("REMAINING"); remaining = text("-- Ah", 21, NAVY, true); ah.addView(remaining, margin(0, 8));
        row2.addView(watts, weighted());
        row2.addView(ah, weighted());
        body.addView(row2, margin(0, 9));

        LinearLayout detail = card();
        capacity = text("Rated capacity: -- Ah", 13, NAVY, true);
        detail.addView(capacity);
        sampleTime = text("Last sample: --", 12, MUTED, false);
        detail.addView(sampleTime, margin(0, 5));
        body.addView(detail, margin(0, 9));

        LinearLayout actions = card();
        actions.addView(text("BLUETOOTH CONNECTION", 13, NAVY, true));
        scanHint = text("Tap Scan to discover ECO039E, or use a saved address.", 12, MUTED, false);
        actions.addView(scanHint, margin(0, 4));
        LinearLayout scanActions = horizontal();
        TextView scan = action("Scan nearby", true);
        scan.setOnClickListener(v -> startScan());
        scanActions.addView(scan, weighted());
        TextView reconnect = action("Reconnect", false);
        reconnect.setOnClickListener(v -> connectSaved());
        knownButton = reconnect;
        scanActions.addView(reconnect, weighted());
        actions.addView(scanActions, margin(0, 10));

        LinearLayout secondary = horizontal();
        TextView disconnect = action("Disconnect", false);
        disconnect.setOnClickListener(v -> {
            ble.disconnect();
            onStatus("Disconnected", false);
        });
        secondary.addView(disconnect, weighted());
        TextView help = action("Scan help", false);
        help.setOnClickListener(v -> showScanHelp());
        secondary.addView(help, weighted());
        actions.addView(secondary, margin(0, 7));

        devices = stack();
        actions.addView(devices, margin(0, 8));
        String old = getPreferences(0).getString("last_bms", "");
        TextView manualLabel = text("Can't find the device? Connect using its BLE address:", 12, MUTED, false);
        actions.addView(manualLabel, margin(0, 9));
        LinearLayout manualLine = horizontal();
        manualMac = new EditText(this);
        manualMac.setTextSize(13);
        manualMac.setSingleLine(true);
        manualMac.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        manualMac.setHint("Bluetooth MAC address");
        manualMac.setText(old);
        manualMac.setSelectAllOnFocus(true);
        manualMac.setPadding(dp(10), 0, dp(10), 0);
        manualMac.setBackground(shape(LIGHT, 10));
        LinearLayout.LayoutParams edit = new LinearLayout.LayoutParams(0, dp(44), 1);
        edit.rightMargin = dp(6);
        manualLine.addView(manualMac, edit);
        TextView direct = action("Connect", true);
        direct.setOnClickListener(v -> connectManual());
        manualLine.addView(direct, new LinearLayout.LayoutParams(dp(104), dp(44)));
        actions.addView(manualLine, margin(0, 6));
        body.addView(actions, margin(0, 10));

        LinearLayout cells = card();
        cellTitle = text("CELL VOLTAGES", 13, NAVY, true);
        cells.addView(cellTitle);
        cellPanel = stack();
        cellPanel.addView(text("Cell telemetry will appear here if supported by your BMS.", 12, MUTED, false));
        cells.addView(cellPanel, margin(0, 8));
        body.addView(cells, margin(0, 10));

        LinearLayout logs = card();
        LinearLayout logHead = horizontal();
        TextView title = text("BLUETOOTH DIAGNOSTICS", 13, NAVY, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        logHead.addView(title, weighted());
        TextView copy = action("Copy log", false);
        copy.setTextSize(12);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("BMS Monitor log", history.toString()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });
        logHead.addView(copy, new LinearLayout.LayoutParams(dp(95), dp(39)));
        logs.addView(logHead);
        log = text("No events", 11, MUTED, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setMaxLines(12);
        log.setTextIsSelectable(true);
        logs.addView(log, margin(0, 8));
        body.addView(logs, margin(0, 10));

        LinearLayout update = card();
        update.addView(text("APP UPDATES", 13, NAVY, true));
        updaterStatus = text("Version " + BuildConfig.VERSION_NAME + " · GitHub Releases", 12, MUTED, false);
        update.addView(updaterStatus, margin(0, 5));
        TextView check = action("Check for updates", false);
        check.setOnClickListener(v -> updates.check(true, this::showUpdateMessage));
        update.addView(check, margin(0, 8));
        body.addView(update, margin(0, 10));

        body.addView(text("Experimental monitor only · Not a safety or protection controller",
                11, MUTED, false), margin(0, 12));
    }

    private void showUpdateMessage(String message) {
        runOnUiThread(() -> { if (updaterStatus != null) updaterStatus.setText(message); });
    }

    private void startScan() {
        if (!ble.bluetoothEnabled()) {
            new AlertDialog.Builder(this).setTitle("Bluetooth is off")
                    .setMessage("Turn on Bluetooth so BMS Monitor can discover ECO039E.")
                    .setPositiveButton("Enable Bluetooth", (d,w) -> {
                        try { startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)); }
                        catch (Exception e) { showScanHelp(); }
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        if (!hasBlePermissions()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[] {
                        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
                }, REQUEST_BLE);
            } else {
                requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQUEST_BLE);
            }
            return;
        }
        if (Build.VERSION.SDK_INT <= 30) {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            boolean enabled = manager == null || (Build.VERSION.SDK_INT >= 28
                    ? manager.isLocationEnabled()
                    : manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if (!enabled) {
                new AlertDialog.Builder(this).setTitle("Location services are off")
                        .setMessage("On this Android version, Bluetooth scanning requires Location services on, even though BMS Monitor does not collect your location.")
                        .setPositiveButton("Open settings", (d,w) ->
                                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton("Cancel", null).show();
                return;
            }
        }
        scanned.clear();
        devices.removeAllViews();
        scanHint.setText("Scanning for 18 seconds… keep the Gentai app closed.");
        devices.addView(text("Looking for Bluetooth LE devices…", 12, MUTED, false));
        ble.scan();
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQUEST_BLE) {
            if (hasBlePermissions()) startScan();
            else new AlertDialog.Builder(this).setTitle("Bluetooth permission required")
                    .setMessage("Please allow Nearby devices (or Location on older Android) in app permissions to scan for your BMS.")
                    .setPositiveButton("OK", null).show();
        }
    }

    private void connectSaved() {
        String saved = getPreferences(0).getString("last_bms", "");
        if (saved.isEmpty()) {
            Toast.makeText(this, "No saved device. Scan or enter its BLE address first.", Toast.LENGTH_LONG).show();
            return;
        }
        ble.connectByAddress(saved);
    }

    private void connectManual() {
        String mac = manualMac.getText().toString().trim().toUpperCase(Locale.US);
        if (!mac.matches("(?i)[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            manualMac.setError("Use XX:XX:XX:XX:XX:XX");
            return;
        }
        if (!hasBlePermissions()) {
            Toast.makeText(this, "Tap Scan once to grant Bluetooth permissions first", Toast.LENGTH_LONG).show();
            startScan();
            return;
        }
        getPreferences(0).edit().putString("last_bms", mac).apply();
        ble.connectByAddress(mac);
    }

    private void showScanHelp() {
        new AlertDialog.Builder(this).setTitle("Finding your Gentai BMS")
                .setMessage("1. Fully close the Gentai and JBD apps. Only one phone/app may hold the BMS connection.\n\n"
                        + "2. Allow Nearby devices permission; on Android 11 or earlier, turn on Location services.\n\n"
                        + "3. Keep your phone within 1 metre of the battery and tap Scan nearby.\n\n"
                        + "4. If nRF Connect can connect but this app cannot find it, copy the BLE MAC address from nRF Connect and use Connect by address.\n\n"
                        + "If a connection fails, copy the Bluetooth diagnostics log.")
                .setPositiveButton("OK", null).show();
    }

    @SuppressLint("MissingPermission")
    @Override public void onDevice(BluetoothDevice device, String name, int rssi) {
        runOnUiThread(() -> {
            if (!scanned.add(device.getAddress())) return;
            if (scanned.size() == 1) devices.removeAllViews();
            boolean bms = name.toUpperCase(Locale.US).contains("ECO") ||
                    name.toUpperCase(Locale.US).contains("BMC") ||
                    name.toUpperCase(Locale.US).contains("JBD");
            TextView item = text((bms ? "●  " : "○  ") + name +
                    "   " + rssi + " dBm\n" + device.getAddress() +
                    (bms ? "  ·  likely BMS" : ""), 13, bms ? BLUE : NAVY, bms);
            item.setPadding(dp(11), dp(9), dp(11), dp(9));
            item.setBackground(shape(bms ? Color.rgb(220, 244, 240) : LIGHT, 10));
            item.setOnClickListener(v -> {
                getPreferences(0).edit().putString("last_bms", device.getAddress()).apply();
                manualMac.setText(device.getAddress());
                ble.connect(device);
            });
            devices.addView(item, bms ? 0 : devices.getChildCount(), margin(0, 5));
            scanHint.setText(scanned.size() + " BLE device(s) discovered · tap ECO039E to connect");
        });
    }

    @Override public void onStatus(String message, boolean ready) {
        runOnUiThread(() -> {
            if (status != null) {
                status.setText((ready ? "●  " : "○  ") + message);
                status.setTextColor(ready ? Color.rgb(102, 245, 182) : Color.WHITE);
            }
            if (scanHint != null && (message.contains("Scan finished") ||
                    message.contains("No devices") || message.contains("Scan error")))
                scanHint.setText(message);
        });
    }

    @Override public void onInfo(BatteryProtocol.BatteryInfo info) {
        runOnUiThread(() -> {
            soc.setText(String.format(Locale.US, "%.0f%%", info.socPercent));
            voltage.setText(String.format(Locale.US, "%.2f V", info.voltageV));
            current.setText(String.format(Locale.US, "%+.2f A", info.currentA));
            power.setText(String.format(Locale.US, "%+.1f W", info.powerW));
            remaining.setText(String.format(Locale.US, "%.2f Ah", info.remainingAh));
            capacity.setText(String.format(Locale.US, "Rated capacity: %.1f Ah", info.totalAh));
            direction.setText(info.currentA < -0.03 ? "Discharging" :
                    info.currentA > 0.03 ? "Charging" : "Idle");
            sampleTime.setText("Last sample: " +
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        });
    }

    @Override public void onCells(double[] cells) {
        runOnUiThread(() -> {
            if (cells.length == 0) return;
            double min = Double.MAX_VALUE, max = 0;
            for (double v : cells) { min = Math.min(min, v); max = Math.max(max, v); }
            cellTitle.setText(String.format(Locale.US,
                    "CELL VOLTAGES · %d cells · Δ %.0f mV", cells.length, (max - min) * 1000));
            cellPanel.removeAllViews();
            for (int start = 0; start < cells.length; start += 4) {
                LinearLayout line = horizontal();
                for (int i = start; i < Math.min(start + 4, cells.length); i++) {
                    TextView val = text(String.format(Locale.US, "%02d\n%.3f V", i+1, cells[i]), 12, NAVY, true);
                    val.setGravity(Gravity.CENTER);
                    val.setBackground(shape(LIGHT, 9));
                    LinearLayout.LayoutParams p = weighted();
                    p.topMargin = dp(4);
                    p.bottomMargin = dp(4);
                    line.addView(val, p);
                }
                cellPanel.addView(line);
            }
        });
    }

    @Override public void onLog(String entry) {
        runOnUiThread(() -> {
            history.insert(0, new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())
                    + "  " + entry + "\n");
            if (history.length() > 12000) history.setLength(12000);
            if (log != null) log.setText(history.toString());
        });
    }

    @Override protected void onDestroy() {
        if (ble != null) ble.close();
        super.onDestroy();
    }

    private LinearLayout stack() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        return container;
    }
    private LinearLayout horizontal() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        return container;
    }
    private LinearLayout card() {
        LinearLayout container = stack();
        container.setPadding(dp(14), dp(12), dp(14), dp(12));
        container.setBackground(shape(Color.WHITE, 15));
        container.setElevation(dp(1));
        return container;
    }
    private LinearLayout metric(String heading) {
        LinearLayout out = card();
        out.addView(text(heading, 11, MUTED, true));
        return out;
    }
    private TextView text(String label, int sp, int color, boolean bold) {
        TextView result = new TextView(this);
        result.setText(label);
        result.setTextColor(color);
        result.setTextSize(sp);
        if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return result;
    }
    private TextView action(String label, boolean primary) {
        TextView control = text(label, 13, primary ? Color.WHITE : BLUE, true);
        control.setGravity(Gravity.CENTER);
        control.setMinHeight(dp(42));
        control.setPadding(dp(7), dp(8), dp(7), dp(8));
        control.setBackground(shape(primary ? BLUE : LIGHT, 10));
        control.setClickable(true);
        return control;
    }
    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private LinearLayout.LayoutParams margin(int left, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        params.leftMargin = dp(left);
        return params;
    }
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        return params;
    }
    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }
}
