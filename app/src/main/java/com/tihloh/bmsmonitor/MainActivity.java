package com.tihloh.bmsmonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.provider.Settings;
import android.location.LocationManager;
import android.net.Uri;
import android.app.AlertDialog;
import android.widget.ProgressBar;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Compact experimental BMS dashboard; all telemetry stays on the phone. */
public final class MainActivity extends Activity implements BleClient.Listener {
    private static final int PERMISSION_REQUEST = 50;
    private static final int INK = Color.rgb(22, 42, 57);
    private static final int MUTED = Color.rgb(99, 116, 132);
    private static final int TEAL = Color.rgb(14, 120, 141);
    private static final int BG = Color.rgb(244, 248, 251);

    private BleClient ble;
    private LinearLayout page;
    private LinearLayout deviceList;
    private LinearLayout cellsContainer;
    private TextView connection;
    private TextView socValue;
    private TextView condition;
    private TextView voltage;
    private TextView current;
    private TextView power;
    private TextView remaining;
    private TextView capacity;
    private TextView updated;
    private TextView cellsTitle;
    private TextView logView;
    private TextView searchHint;
    private TextView deviceCount;
    private ProgressBar scanProgress;
    private Button scanButton;
    private boolean autoConnectPending;
    private boolean checkOnce = false;
    private AppUpdater updater;
    private final StringBuilder logs = new StringBuilder();
    private final Map<String, BluetoothDevice> nearby = new LinkedHashMap<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(15, 37, 58));
        getWindow().setNavigationBarColor(Color.rgb(15, 37, 58));
        ble = new BleClient(this, this);
        updater = new AppUpdater(this);
        buildUi();
        checkForUpdates(false);
        onStatus("Not connected — tap Scan for BMS", false);
        onLog("Experimental read-only Gentai/ECO BLE monitor ready.");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(17), dp(22), dp(17), dp(26));
        scroll.addView(page);
        setContentView(scroll);

        LinearLayout headingRow = horizontal();
        LinearLayout headingTexts = new LinearLayout(this);
        headingTexts.setOrientation(LinearLayout.VERTICAL);
        headingTexts.addView(label("BMS Monitor", 25, INK, true));
        TextView sub = label("LIVE BATTERY  /  BLE", 11, MUTED, true);
        sub.setLetterSpacing(0.07f);
        headingTexts.addView(sub, withTop(0, 3));
        headingRow.addView(headingTexts, new LinearLayout.LayoutParams(0, -2, 1));
        Button updates = button("Updates", false);
        updates.setTextSize(12);
        updates.setOnClickListener(v -> checkForUpdates(true));
        headingRow.addView(updates, new LinearLayout.LayoutParams(dp(105), dp(46)));
        page.addView(headingRow);

        LinearLayout connectionCard = card();
        connection = label("Not connected", 14, INK, true);
        connectionCard.addView(connection);
        connectionCard.addView(label("ECO039E / Gentai • Read-only battery telemetry", 12, MUTED, false),
                withTop(0, 6));
        page.addView(connectionCard, withTop(0, 16));

        LinearLayout scanCard = card();
        LinearLayout scanTitleRow = horizontal();
        scanTitleRow.addView(label("Bluetooth devices", 17, INK, true),
                new LinearLayout.LayoutParams(0, dp(40), 1));
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        scanProgress.setVisibility(View.GONE);
        scanTitleRow.addView(scanProgress, new LinearLayout.LayoutParams(dp(36), dp(36)));
        scanCard.addView(scanTitleRow);
        searchHint = label("Tap Scan to find your Gentai BMS.", 13, MUTED, false);
        scanCard.addView(searchHint);
        scanButton = button("Scan for nearby BMS", true);
        scanButton.setOnClickListener(v -> requestScan());
        scanCard.addView(scanButton, withTop(0, 12));
        deviceCount = label("Tip: Force-stop the Gentai app before connecting.", 12, MUTED, false);
        scanCard.addView(deviceCount, withTop(0, 7));
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        scanCard.addView(deviceList, withTop(0, 5));
        Button help = button("Bluetooth troubleshooting", false);
        help.setTextSize(12);
        help.setOnClickListener(v -> showBluetoothHelp());
        scanCard.addView(help, withTop(0, 8));
        page.addView(scanCard, withTop(0, 12));

        LinearLayout actions = horizontal();
        Button refresh = button("Refresh", false);
        Button disconnect = button("Disconnect", false);
        refresh.setOnClickListener(v -> ble.refresh());
        disconnect.setOnClickListener(v -> {
            ble.disconnect();
            onStatus("Disconnected — tap Scan to reconnect", false);
        });
        actions.addView(refresh, weightedButton());
        actions.addView(disconnect, weightedButton());
        page.addView(actions, withTop(0, 3));

        // Results are displayed inside the Bluetooth devices card above.

        LinearLayout chargeCard = card();
        chargeCard.addView(label("STATE OF CHARGE", 12, MUTED, true));
        socValue = label("--%", 48, TEAL, true);
        chargeCard.addView(socValue);
        condition = label("Waiting for battery data", 13, MUTED, false);
        chargeCard.addView(condition);
        page.addView(chargeCard, withTop(0, 10));

        LinearLayout firstMetrics = horizontal();
        LinearLayout voltsBox = metric("VOLTAGE", "-- V");
        voltage = (TextView) voltsBox.getChildAt(1);
        LinearLayout ampsBox = metric("CURRENT", "-- A");
        current = (TextView) ampsBox.getChildAt(1);
        firstMetrics.addView(voltsBox, weightedCard());
        firstMetrics.addView(ampsBox, weightedCard());
        page.addView(firstMetrics, withTop(0, 10));

        LinearLayout secondMetrics = horizontal();
        LinearLayout powerBox = metric("POWER", "-- W");
        power = (TextView) powerBox.getChildAt(1);
        LinearLayout remainBox = metric("REMAINING", "-- Ah");
        remaining = (TextView) remainBox.getChildAt(1);
        secondMetrics.addView(powerBox, weightedCard());
        secondMetrics.addView(remainBox, weightedCard());
        page.addView(secondMetrics, withTop(0, 10));

        LinearLayout detailCard = card();
        capacity = label("Rated capacity: -- Ah", 14, INK, true);
        detailCard.addView(capacity);
        updated = label("Last update: --", 12, MUTED, false);
        detailCard.addView(updated, withTop(0, 6));
        page.addView(detailCard, withTop(0, 10));

        LinearLayout cellsCard = card();
        cellsTitle = label("Cell voltages · awaiting supported packet", 14, INK, true);
        cellsCard.addView(cellsTitle);
        cellsContainer = new LinearLayout(this);
        cellsContainer.setOrientation(LinearLayout.VERTICAL);
        cellsCard.addView(cellsContainer, withTop(0, 9));
        page.addView(cellsCard, withTop(0, 10));

        LinearLayout logCard = card();
        LinearLayout logHeading = horizontal();
        TextView logLabel = label("Bluetooth log", 15, INK, true);
        logHeading.addView(logLabel, new LinearLayout.LayoutParams(0, dp(44), 1));
        logLabel.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button copy = button("Copy log", false);
        copy.setTextSize(12);
        copy.setOnClickListener(v -> {
            ClipboardManager c = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (c != null) {
                c.setPrimaryClip(ClipData.newPlainText("BMS Monitor BLE log", logs.toString()));
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
            }
        });
        logHeading.addView(copy);
        logCard.addView(logHeading);
        logView = label("No events yet", 11, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMaxLines(12);
        logCard.addView(logView);
        page.addView(logCard, withTop(0, 10));

        page.addView(label("Experimental monitor • values are not safety controls", 11, MUTED, false),
                withTop(0, 14));
    }

    private void requestScan() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= 31) {
            permissions = new String[] {
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            permissions = new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
        }
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, PERMISSION_REQUEST);
                return;
            }
        }
        doScan();
    }

    @SuppressLint("MissingPermission")
    private void doScan() {
        nearby.clear();
        autoConnectPending = !getPreferences(MODE_PRIVATE).getString("last_bms", "").isEmpty();
        deviceList.removeAllViews();
        searchHint.setText("Scanning for 18 seconds…  Keep phone near the battery.");
        deviceCount.setText("Looking for ECO039E / BMC1_039E and other BLE devices.");
        scanProgress.setVisibility(View.VISIBLE);
        scanButton.setText("Scan again");
        ble.scan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        boolean granted = results.length > 0;
        for (int value : results) if (value != PackageManager.PERMISSION_GRANTED) granted = false;
        if (granted) doScan();
        else {
            scanProgress.setVisibility(View.GONE);
            searchHint.setText("Nearby Devices permission is required. Grant it in Android Settings.");
            onStatus("Bluetooth permission denied", false);
        }
    }

    @Override public void onStatus(String status, boolean ready) {
        runOnUiThread(() -> {
            if (connection != null) {
                connection.setText((ready ? "●  " : "○  ") + status);
                connection.setTextColor(ready ? TEAL : INK);
                if (ready) {
                    scanProgress.setVisibility(View.GONE);
                    searchHint.setText("Connected. Readings update automatically.");
                }
            }
        });
    }

    @Override public void onScanComplete(int count) {
        runOnUiThread(() -> {
            scanProgress.setVisibility(View.GONE);
            if (count == 0) {
                searchHint.setText("No devices found. Confirm Bluetooth is ON, then tap Scan again.");
                deviceCount.setText("Try enabling Location and moving closer to the battery.");
            } else {
                searchHint.setText("Scan finished. Tap ECO039E or another supported BLE device.");
                deviceCount.setText(count + " Bluetooth device(s) detected.");
            }
        });
    }

    @SuppressLint("MissingPermission")
    @Override public void onDevice(BluetoothDevice device, String name, int rssi) {
        if (nearby.containsKey(device.getAddress())) return;
        nearby.put(device.getAddress(), device);
        if (nearby.size() == 1) deviceList.removeAllViews();
        final boolean preferred = name.toUpperCase(Locale.US).startsWith("ECO")
                || name.toUpperCase(Locale.US).contains("BMC")
                || device.getAddress().equals(getPreferences(MODE_PRIVATE).getString("last_bms", ""));
        Button choice = button((preferred ? "🔋  " : "◌  ") + name
                + "  ·  " + rssi + " dBm", preferred);
        choice.setMinHeight(dp(48));
        choice.setAllCaps(false);
        choice.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        choice.setOnClickListener(v -> {
            deviceList.removeAllViews();
            deviceList.addView(label("Selected: " + name + " (" + device.getAddress() + ")", 12, MUTED, false));
            getPreferences(MODE_PRIVATE).edit().putString("last_bms", device.getAddress()).apply();
            scanProgress.setVisibility(View.GONE);
            searchHint.setText("Connecting to " + name + "…");
            ble.connect(device);
        });
        if (preferred) deviceList.addView(choice, 0, withTop(0, 5));
        else deviceList.addView(choice, withTop(0, 5));
        deviceCount.setText(nearby.size() + " device(s) found. Select ECO039E.");
        if (autoConnectPending && device.getAddress().equals(
                getPreferences(MODE_PRIVATE).getString("last_bms", ""))) {
            autoConnectPending = false;
            choice.performClick();
        }
    }

    @Override public void onInfo(BatteryProtocol.BatteryInfo info) {
        runOnUiThread(() -> {
            socValue.setText(String.format(Locale.US, "%.0f%%", info.socPercent));
            voltage.setText(String.format(Locale.US, "%.2f V", info.voltageV));
            current.setText(String.format(Locale.US, "%+.2f A", info.currentA));
            power.setText(String.format(Locale.US, "%+.1f W", info.powerW));
            remaining.setText(String.format(Locale.US, "%.2f Ah", info.remainingAh));
            capacity.setText(String.format(Locale.US, "Rated capacity: %.1f Ah", info.totalAh));
            condition.setText(info.currentA < -0.03 ? "Discharging" :
                    info.currentA > 0.03 ? "Charging" : "Idle");
            updated.setText("Last update: " +
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        });
    }

    @Override public void onCells(double[] cells) {
        runOnUiThread(() -> {
            cellsContainer.removeAllViews();
            if (cells.length == 0) return;
            double min = Double.MAX_VALUE, max = 0;
            for (double v : cells) { min = Math.min(min, v); max = Math.max(max, v); }
            cellsTitle.setText(String.format(Locale.US,
                    "%d cells · range %.3f–%.3f V · Δ %.0f mV",
                    cells.length, min, max, (max - min) * 1000));
            for (int first = 0; first < cells.length; first += 4) {
                LinearLayout row = horizontal();
                for (int i = first; i < Math.min(first + 4, cells.length); i++) {
                    TextView item = label(String.format(Locale.US, "%02d\n%.3f V", i + 1, cells[i]),
                            12, INK, true);
                    item.setGravity(android.view.Gravity.CENTER);
                    item.setBackground(rounded(Color.rgb(237, 247, 249), 10));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1);
                    lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                    row.addView(item, lp);
                }
                cellsContainer.addView(row);
            }
        });
    }

    @Override public void onLog(String entry) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            logs.insert(0, time + "  " + entry + "\n");
            if (logs.length() > 12000) logs.setLength(12000);
            if (logView != null) logView.setText(logs.toString());
        });
    }

    private void showBluetoothHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Cannot find ECO039E?")
                .setMessage("1. Force-stop the Gentai and nRF Connect apps.\n\n" +
                        "2. Keep Bluetooth and Android Location switched on.\n\n" +
                        "3. Allow Nearby Devices permission for BMS Monitor.\n\n" +
                        "4. Move your phone within 1 metre of the battery.\n\n" +
                        "5. Tap Scan again and wait for 18 seconds.")
                .setNegativeButton("Close", null)
                .setPositiveButton("App settings", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }).show();
    }

    private void checkForUpdates(boolean manual) {
        if (!manual && checkOnce) return;
        checkOnce = true;
        updater.check(manual, new AppUpdater.Callback() {
            @Override public void onAvailable(AppUpdater.Update update) {
                runOnUiThread(() -> {
                    String notes = update.notes.length() > 400 ?
                            update.notes.substring(0, 400) + "…" : update.notes;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("BMS Monitor " + update.version + " is available")
                            .setMessage("Install the latest version? Android will ask for permission.\n\n" + notes)
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Update", (d, w) -> updater.install(MainActivity.this,
                                    update, message -> runOnUiThread(() ->
                                            Toast.makeText(MainActivity.this, message,
                                                    Toast.LENGTH_LONG).show())))
                            .show();
                });
            }
            @Override public void onNoUpdate(String reason) {
                if (manual) runOnUiThread(() -> Toast.makeText(
                        MainActivity.this, reason, Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override protected void onDestroy() {
        if (ble != null) ble.close();
        super.onDestroy();
    }

    private LinearLayout metric(String title, String value) {
        LinearLayout out = card();
        out.addView(label(title, 11, MUTED, true));
        TextView big = label(value, 21, INK, true);
        out.addView(big, withTop(0, 9));
        return out;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(15), dp(15), dp(15), dp(15));
        l.setBackground(rounded(Color.WHITE, 17));
        l.setElevation(dp(2));
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : INK);
        b.setBackgroundTintList(null);
        b.setBackground(rounded(primary ? TEAL : Color.rgb(227, 237, 243), 11));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setMinHeight(dp(44));
        return b;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextColor(color);
        l.setTextSize(size);
        if (bold) l.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return l;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout.LayoutParams withTop(int width, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                width <= 0 ? LinearLayout.LayoutParams.MATCH_PARENT : dp(width),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    private LinearLayout.LayoutParams weightedCard() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        return lp;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        return lp;
    }

    private int dp(float v) { return (int) (getResources().getDisplayMetrics().density * v + 0.5f); }
}
