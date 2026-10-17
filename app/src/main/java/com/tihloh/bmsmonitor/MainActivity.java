package com.tihloh.bmsmonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
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
    private final StringBuilder logs = new StringBuilder();
    private final Map<String, BluetoothDevice> nearby = new LinkedHashMap<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(15, 37, 58));
        getWindow().setNavigationBarColor(Color.rgb(15, 37, 58));
        ble = new BleClient(this, this);
        buildUi();
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

        TextView heading = label("BMS Monitor", 26, INK, true);
        page.addView(heading);
        TextView sub = label("GENTAI  •  BLUETOOTH EXPERIMENT", 11, MUTED, true);
        sub.setLetterSpacing(0.09f);
        page.addView(sub, withTop(0, 6));

        LinearLayout connectionCard = card();
        connection = label("Not connected", 14, INK, true);
        connectionCard.addView(connection);
        connectionCard.addView(label("BLE telemetry only · no BMS settings are changed", 12, MUTED, false),
                withTop(0, 6));
        page.addView(connectionCard, withTop(0, 16));

        Button scan = button("Scan for BMS", true);
        scan.setOnClickListener(v -> requestScan());
        page.addView(scan, withTop(0, 13));

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

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        page.addView(deviceList, withTop(0, 4));

        LinearLayout chargeCard = card();
        chargeCard.addView(label("STATE OF CHARGE", 12, MUTED, true));
        socValue = label("--%", 48, TEAL, true);
        chargeCard.addView(socValue);
        condition = label("Waiting for battery data", 13, MUTED, false);
        chargeCard.addView(condition);
        page.addView(chargeCard, withTop(0, 18));

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

        page.addView(label("Prototype • not a battery protection instrument", 11, MUTED, false),
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
        deviceList.removeAllViews();
        deviceList.addView(label("Scanning… Tap the ECO039E device when it appears.", 12, MUTED, false),
                withTop(0, 8));
        ble.scan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        boolean granted = results.length > 0;
        for (int value : results) if (value != PackageManager.PERMISSION_GRANTED) granted = false;
        if (granted) doScan();
        else onStatus("Bluetooth permission is required to scan", false);
    }

    @Override public void onStatus(String status, boolean ready) {
        runOnUiThread(() -> {
            if (connection != null) {
                connection.setText((ready ? "●  " : "○  ") + status);
                connection.setTextColor(ready ? TEAL : INK);
            }
        });
    }

    @SuppressLint("MissingPermission")
    @Override public void onDevice(BluetoothDevice device, String name, int rssi) {
        if (nearby.containsKey(device.getAddress())) return;
        nearby.put(device.getAddress(), device);
        if (nearby.size() == 1) deviceList.removeAllViews();
        Button choice = button(name + "  ·  " + rssi + " dBm", false);
        choice.setAllCaps(false);
        choice.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        choice.setOnClickListener(v -> {
            deviceList.removeAllViews();
            deviceList.addView(label("Selected: " + name + " (" + device.getAddress() + ")", 12, MUTED, false));
            ble.connect(device);
        });
        deviceList.addView(choice, withTop(0, 5));
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
