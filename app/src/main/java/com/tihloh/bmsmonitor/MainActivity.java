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
import android.view.Gravity;
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

/** Lightweight, high-contrast native Android interface for BMS telemetry. */
public final class MainActivity extends Activity implements BleClient.Listener {
    private static final int REQUEST_BLE = 61;
    private static final int NAVY = 0xFF142A3E, TEAL = 0xFF087B82;
    private static final int MUTED = 0xFF667B8A, PAPER = 0xFFF4F7FA, LINE = 0xFFDDE6ED;
    private static final int WHITE = Color.WHITE, ORANGE = 0xFFB46E1A;
    private final LinkedHashMap<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private final StringBuilder logs = new StringBuilder();
    private BleClient ble;
    private UpdateClient updater;
    private LinearLayout root, deviceList, cellList, logSection;
    private TextView statusText, scanHint, deviceCount, soc, batteryState, voltage, current,
            power, remaining, capacity, updatedAt, cellHeading, logText, versionText;
    private Button scanButton, reconnectButton;
    private boolean scanning = false, connected = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        ble = new BleClient(this, this);
        updater = new UpdateClient(this, new UpdateClient.Listener() {
            @Override public void onAvailable(UpdateClient.Release release) {
                runOnUiThread(() -> showUpdatePrompt(release));
            }
            @Override public void onMessage(String message) {
                runOnUiThread(() -> {
                    onLog("Updater: " + message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
        draw();
        onStatus("Ready — searching for Bluetooth batteries", false);
        updater.check(false);
        root.postDelayed(this::startScanWithPermissions, 600);
    }

    private void draw() {
        ScrollView scrolling = new ScrollView(this);
        scrolling.setFillViewport(true);
        scrolling.setBackgroundColor(PAPER);
        root = column();
        root.setPadding(dp(16), dp(21), dp(16), dp(30));
        scrolling.addView(root);
        setContentView(scrolling);

        LinearLayout titleRow = row();
        LinearLayout titles = column();
        titles.addView(text("BMS Monitor", 25, NAVY, true));
        titles.addView(text("GENTAI  •  BLUETOOTH BATTERY", 11, MUTED, true), gap(0, 3));
        titleRow.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        versionText = text("v" + BuildConfig.VERSION_NAME, 12, MUTED, true);
        versionText.setGravity(Gravity.RIGHT);
        titleRow.addView(versionText);
        root.addView(titleRow);

        LinearLayout stateCard = card();
        statusText = text("Scanning…", 15, NAVY, true);
        stateCard.addView(statusText);
        stateCard.addView(text("Live BLE · Read-only monitoring", 12, MUTED, false), gap(0, 5));
        root.addView(stateCard, gap(0, 17));

        LinearLayout buttons = row();
        scanButton = button("Scan again", true);
        scanButton.setOnClickListener(v -> startScanWithPermissions());
        reconnectButton = button("Reconnect", false);
        reconnectButton.setOnClickListener(v -> reconnectLast());
        buttons.addView(scanButton, weight());
        buttons.addView(reconnectButton, weight());
        root.addView(buttons, gap(0, 9));

        LinearLayout controls = row();
        Button updates = button("Check updates", false);
        updates.setOnClickListener(v -> updater.check(true));
        Button logsButton = button("Bluetooth log", false);
        controls.addView(updates, weight());
        controls.addView(logsButton, weight());
        root.addView(controls, gap(0, 4));

        LinearLayout deviceCard = card();
        LinearLayout dTitle = row();
        dTitle.addView(text("Nearby BLE devices", 16, NAVY, true), new LinearLayout.LayoutParams(0, -2, 1));
        deviceCount = text("0 found", 12, MUTED, true);
        dTitle.addView(deviceCount);
        deviceCard.addView(dTitle);
        scanHint = text("Scanning automatically. Keep the battery nearby.", 12, MUTED, false);
        deviceCard.addView(scanHint, gap(0, 5));
        deviceList = column();
        deviceCard.addView(deviceList, gap(0, 8));
        root.addView(deviceCard, gap(0, 12));

        LinearLayout chargeCard = card();
        chargeCard.addView(text("BATTERY STATE OF CHARGE", 11, MUTED, true));
        soc = text("--%", 48, TEAL, true);
        chargeCard.addView(soc, gap(0, 3));
        batteryState = text("Connect to your Gentai BMS to view readings", 13, MUTED, false);
        chargeCard.addView(batteryState);
        root.addView(chargeCard, gap(0, 11));

        LinearLayout r1 = row();
        LinearLayout v = metric("VOLTAGE");
        voltage = (TextView) v.getChildAt(1);
        LinearLayout a = metric("CURRENT");
        current = (TextView) a.getChildAt(1);
        r1.addView(v, weight());
        r1.addView(a, weight());
        root.addView(r1, gap(0, 10));

        LinearLayout r2 = row();
        LinearLayout p = metric("POWER");
        power = (TextView) p.getChildAt(1);
        LinearLayout rem = metric("REMAINING");
        remaining = (TextView) rem.getChildAt(1);
        r2.addView(p, weight());
        r2.addView(rem, weight());
        root.addView(r2, gap(0, 10));

        LinearLayout details = card();
        capacity = text("Rated capacity: -- Ah", 13, NAVY, true);
        updatedAt = text("Last reading: --", 12, MUTED, false);
        details.addView(capacity);
        details.addView(updatedAt, gap(0, 5));
        root.addView(details, gap(0, 10));

        LinearLayout cells = card();
        cellHeading = text("Individual cell voltages", 15, NAVY, true);
        cells.addView(cellHeading);
        cellList = column();
        cellList.addView(text("Awaiting a supported cell-voltage response", 12, MUTED, false));
        cells.addView(cellList, gap(0, 9));
        root.addView(cells, gap(0, 10));

        logSection = card();
        logSection.addView(text("Connection diagnostics", 15, NAVY, true));
        Button copy = button("Copy diagnostic log", false);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("BMS Monitor", logs.toString()));
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
            }
        });
        logSection.addView(copy, gap(0, 7));
        logText = text("No events yet.", 11, MUTED, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setMaxLines(24);
        logSection.addView(logText);
        logSection.setVisibility(View.GONE);
        logsButton.setOnClickListener(v -> logSection.setVisibility(
                logSection.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        root.addView(logSection, gap(0, 10));

        root.addView(text("Experimental telemetry only. Use your BMS for protection.", 11, MUTED, false), gap(0, 14));
    }

    private boolean permissionGranted() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScanWithPermissions() {
        if (!ble.hasBluetoothHardware()) {
            onStatus("This phone does not support Bluetooth", false);
            return;
        }
        if (!permissionGranted()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[] {
                        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
                }, REQUEST_BLE);
            } else {
                requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQUEST_BLE);
            }
            return;
        }
        if (!ble.bluetoothEnabled()) {
            onStatus("Turn Bluetooth ON and tap Scan again", false);
            new AlertDialog.Builder(this).setTitle("Bluetooth is off")
                    .setMessage("Enable Bluetooth, then return and scan again.")
                    .setPositiveButton("Bluetooth settings", (d, which) ->
                            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= 30) {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (manager != null && !manager.isLocationEnabled()) {
                onStatus("Turn on phone Location for BLE scanning", false);
                new AlertDialog.Builder(this).setTitle("Location service required")
                        .setMessage("On Android 11 and earlier, Bluetooth discovery requires Location to be enabled.")
                        .setPositiveButton("Location settings", (d, which) ->
                                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton("Later", null).show();
                return;
            }
        }
        scanning = true;
        devices.clear();
        deviceList.removeAllViews();
        deviceCount.setText("0 found");
        scanHint.setText("Scanning for 20 seconds… Select ECO039E or BMC1_039E.");
        onStatus("Scanning nearby Bluetooth LE batteries…", false);
        ble.scan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_BLE) return;
        if (permissionGranted()) {
            startScanWithPermissions();
        } else {
            onStatus("Nearby devices permission denied. Grant it in Android Settings.", false);
            scanHint.setText("Open Settings → Apps → BMS Monitor → Permissions → Nearby devices.");
            new AlertDialog.Builder(this).setTitle("Permission needed")
                    .setMessage("Allow Nearby devices so BMS Monitor can find ECO039E. Your location is not uploaded.")
                    .setPositiveButton("App settings", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }).setNegativeButton("Later", null).show();
        }
    }

    @SuppressLint("MissingPermission")
    private void reconnectLast() {
        if (!permissionGranted()) { startScanWithPermissions(); return; }
        if (!ble.bluetoothEnabled()) { startScanWithPermissions(); return; }
        String address = getPreferences(0).getString("last_bms", "");
        if (address.isEmpty()) { startScanWithPermissions(); return; }
        try {
            android.bluetooth.BluetoothManager manager =
                    (android.bluetooth.BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothDevice device = manager.getAdapter().getRemoteDevice(address);
            ble.connect(device);
        } catch (Exception e) {
            onLog("Reconnection failed: " + e.getMessage());
            startScanWithPermissions();
        }
    }

    @Override public void onStatus(String status, boolean ready) {
        runOnUiThread(() -> {
            connected = ready;
            statusText.setText((ready ? "●  " : "○  ") + status);
            statusText.setTextColor(ready ? TEAL : NAVY);
            if (status.toLowerCase(Locale.ROOT).contains("scan finished") || status.startsWith("No BLE")) {
                scanning = false;
                scanHint.setText(devices.isEmpty() ?
                        "No devices detected. Close Gentai, move closer, and rescan." :
                        "Tap ECO039E to connect. Other Bluetooth devices may also appear.");
            }
            if (ready) scanHint.setText("Connected. Live readings refresh automatically.");
        });
    }

    @SuppressLint("MissingPermission")
    @Override public void onDevice(BluetoothDevice device, String name, int rssi) {
        runOnUiThread(() -> {
            if (devices.containsKey(device.getAddress())) return;
            devices.put(device.getAddress(), device);
            deviceCount.setText(devices.size() + " found");
            boolean likely = name.toUpperCase(Locale.ROOT).contains("ECO") ||
                    name.toUpperCase(Locale.ROOT).contains("BMC") ||
                    name.toUpperCase(Locale.ROOT).contains("BMS");
            String label = (likely ? "● " : "○ ") + name + "   " + rssi + " dBm";
            Button item = button(label, likely);
            item.setAllCaps(false);
            item.setTextSize(13);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setContentDescription("Connect to Bluetooth device " + name);
            item.setOnClickListener(v -> {
                getPreferences(0).edit().putString("last_bms", device.getAddress()).apply();
                scanHint.setText("Selected: " + name + " — connecting…");
                ble.connect(device);
            });
            if (likely) deviceList.addView(item, 0, gap(0, 5));
            else deviceList.addView(item, gap(0, 5));
        });
    }

    @Override public void onInfo(BatteryProtocol.BatteryInfo info) {
        runOnUiThread(() -> {
            soc.setText(String.format(Locale.US, "%.0f%%", info.socPercent));
            batteryState.setText(info.currentA < -0.03 ? "Discharging" :
                    info.currentA > 0.03 ? "Charging" : "Idle");
            voltage.setText(String.format(Locale.US, "%.2f V", info.voltageV));
            current.setText(String.format(Locale.US, "%+.2f A", info.currentA));
            power.setText(String.format(Locale.US, "%+.1f W", info.powerW));
            remaining.setText(String.format(Locale.US, "%.2f Ah", info.remainingAh));
            capacity.setText(String.format(Locale.US, "Rated capacity: %.1f Ah", info.totalAh));
            updatedAt.setText("Last reading: " +
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        });
    }

    @Override public void onCells(double[] cells) {
        runOnUiThread(() -> {
            if (cells.length == 0) return;
            double min = Double.MAX_VALUE, max = 0;
            for (double v : cells) { min = Math.min(min, v); max = Math.max(max, v); }
            cellHeading.setText(String.format(Locale.US,
                    "%d cells  ·  Δ %.0f mV", cells.length, (max - min) * 1000));
            cellList.removeAllViews();
            for (int start = 0; start < cells.length; start += 4) {
                LinearLayout row = row();
                for (int i = start; i < Math.min(start + 4, cells.length); i++) {
                    TextView box = text(String.format(Locale.US, "%02d\n%.3f V", i + 1, cells[i]),
                            12, NAVY, true);
                    box.setGravity(Gravity.CENTER);
                    box.setBackground(round(0xFFE8F4F6, 10, 0));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1);
                    lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                    row.addView(box, lp);
                }
                cellList.addView(row);
            }
        });
    }

    @Override public void onLog(String entry) {
        runOnUiThread(() -> {
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            logs.insert(0, stamp + "  " + entry + "\n");
            if (logs.length() > 14000) logs.setLength(14000);
            if (logText != null) logText.setText(logs.toString());
        });
    }

    private void showUpdatePrompt(UpdateClient.Release release) {
        String notes = release.notes == null ? "" : release.notes;
        new AlertDialog.Builder(this)
                .setTitle("BMS Monitor " + release.version + " available")
                .setMessage((notes.isEmpty() ? "A new version is ready." : notes) +
                        "\n\nAndroid will ask for confirmation to install the update.")
                .setPositiveButton("Update", (d, w) -> updater.install(release))
                .setNegativeButton("Later", null).show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (updater != null) updater.check(false);
    }

    @Override protected void onDestroy() {
        if (ble != null) ble.close();
        if (updater != null) updater.close();
        super.onDestroy();
    }

    private LinearLayout metric(String heading) {
        LinearLayout card = card();
        card.addView(text(heading, 11, MUTED, true));
        card.addView(text("--", 21, NAVY, true), gap(0, 8));
        return card;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = column();
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(round(WHITE, 15, LINE));
        return l;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? WHITE : NAVY);
        b.setBackgroundTintList(null);
        b.setBackground(round(primary ? TEAL : 0xFFE5EDF2, 10, 0));
        b.setMinHeight(dp(45));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        return b;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private LinearLayout.LayoutParams gap(int h, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, h == 0 ? -2 : dp(h));
        lp.topMargin = dp(top);
        return lp;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
