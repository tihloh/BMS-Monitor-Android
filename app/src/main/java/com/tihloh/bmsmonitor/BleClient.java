package com.tihloh.bmsmonitor;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** BLE central for the known Gentai/ECO telemetry-only GATT profile. */
@SuppressLint("MissingPermission")
public final class BleClient {
    public interface Listener {
        void onStatus(String status, boolean ready);
        void onDevice(BluetoothDevice device, String name, int rssi, boolean likelyBms);
        void onScanFinished();
        void onInfo(BatteryProtocol.BatteryInfo info);
        void onCells(double[] voltages);
        void onBatteryLevel(int percent);
        void onLog(String entry);
    }

    private static final UUID SERVICE = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final BatteryProtocol.Assembler assembler = new BatteryProtocol.Assembler();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writer;
    private volatile boolean ready;
    private boolean standardBatteryMode;
    private int discoveredCount;
    private volatile boolean scanning;
    private boolean legacyScanning;
    private int pollIndex;
    private boolean writeInProgress;
    private int pendingCommand = -1;

    private void forwardDevice(BluetoothDevice device, String name, int rssi, boolean likely) {
        listener.onDevice(device, name, rssi, likely);
    }

    public BleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean bluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    private void status(String value, boolean connected) {
        handler.post(() -> listener.onStatus(value, connected));
    }

    private void log(String value) {
        handler.post(() -> listener.onLog(value));
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning || result == null) return;
            String name = null;
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null || name.isEmpty()) {
                try { name = result.getDevice().getName(); }
                catch (SecurityException ignored) { }
            }
            final String finalName = name == null || name.isEmpty() ? "Unknown BLE device" : name;
            final BluetoothDevice device = result.getDevice();
            final int rssi = result.getRssi();
            boolean likely = finalName.toLowerCase(java.util.Locale.US).matches(".*(eco|bms|bmc|jbd|xiaoxiang|gentai).*");
            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (android.os.ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                    String u = uuid.getUuid().toString().substring(0, 8);
                    if ("0000ff00".equals(u) || "00000001".equals(u)) likely = true;
                }
            }
            final boolean likelyBms = likely;
            discoveredCount++;
            if (discoveredCount <= 10 || likelyBms)
                log("BLE: " + finalName + " / " + device.getAddress() + " / " + rssi + " dBm");
            handler.post(() -> listener.onDevice(device, finalName, rssi, likelyBms));
        }

        @Override public void onBatchScanResults(java.util.List<ScanResult> results) {
            for (ScanResult result : results) onScanResult(0, result);
        }
        @Override public void onScanFailed(int errorCode) {
            log("Android standard BLE scanner failed: " + errorCode);
            handler.post(() -> { if (scanning) startLegacyScan(); });
        }
    };

    private final BluetoothAdapter.LeScanCallback legacyScanCallback = (device, rssi, bytes) -> {
        if (!scanning || device == null) return;
        String name = null;
        if (bytes != null) {
            // Read the advertised local name even when Android hides the device name.
            for (int i = 0; i < bytes.length;) {
                int len = bytes[i] & 0xFF;
                if (len == 0 || i + len >= bytes.length) break;
                int type = bytes[i + 1] & 0xFF;
                if ((type == 0x08 || type == 0x09) && len > 1) {
                    name = new String(bytes, i + 2, len - 1, java.nio.charset.StandardCharsets.UTF_8);
                    break;
                }
                i += len + 1;
            }
        }
        if (name == null || name.isEmpty()) {
            try { name = device.getName(); } catch (SecurityException ignored) { }
        }
        final String display = name == null || name.isEmpty() ? "Unnamed BLE device" : name;
        boolean likely = display.toLowerCase(java.util.Locale.US)
                .matches(".*(eco|bms|bmc|jbd|xiaoxiang|gentai).*");
        discoveredCount++;
        final boolean possibleBms = likely;
        handler.post(() -> forwardDevice(device, display, rssi, possibleBms));
    };

    private final Runnable compatibilityScanTask = () -> {
        if (scanning && discoveredCount == 0 && !legacyScanning) {
            log("No BLE advertisements from standard scanner after 8 seconds; trying compatibility scan.");
            startLegacyScan();
        }
    };
    private final Runnable stopScanTask = this::stopScan;
    private final Runnable connectionTimeout = () -> {
        if (gatt != null && !ready) {
            log("BLE connection timed out");
            disconnect();
            status("Connection timed out. Close Gentai/nRF Connect, then rescan or retry.", false);
        }
    };
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!ready) return;
            pollIndex++;
            request(0x21);
            if (pollIndex % 3 == 0) {
                handler.postDelayed(() -> {
                    if (ready) request(0x22);
                }, 1200);
            }
            handler.postDelayed(this, 5000);
        }
    };

    public void scan() {
        stopScan();
        discoveredCount = 0;
        if (!bluetoothEnabled()) {
            status("Bluetooth is switched off. Enable it before scanning.", false);
            handler.post(listener::onScanFinished);
            return;
        }
        scanning = true;
        legacyScanning = false;
        log("Starting unfiltered BLE scan (Android API " + Build.VERSION.SDK_INT + ")");
        status("Scanning for Bluetooth LE devices…", false);
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                log("Android returned no modern BLE scanner. Trying compatibility scanner.");
                startLegacyScan();
            } else {
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setReportDelay(0)
                        .build();
                scanner.startScan(null, settings, scanCallback);
                handler.postDelayed(compatibilityScanTask, 8000);
            }
            handler.postDelayed(stopScanTask, 20000);
        } catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
            log("BLE scan exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            startLegacyScan();
            handler.postDelayed(stopScanTask, 20000);
        }
    }

    @SuppressWarnings("deprecation")
    private void startLegacyScan() {
        if (!scanning || legacyScanning) return;
        handler.removeCallbacks(compatibilityScanTask);
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); }
            catch (SecurityException | IllegalStateException ignored) { }
            scanner = null;
        }
        try {
            legacyScanning = adapter != null && adapter.startLeScan(legacyScanCallback);
            log(legacyScanning ? "Compatibility BLE scan started" : "Compatibility BLE scan did not start");
            if (!legacyScanning)
                status("Scanning unavailable. Turn on Bluetooth and Location, then retry.", false);
        } catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
            legacyScanning = false;
            log("Compatibility scan error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            status("Bluetooth scan blocked; review Nearby Devices and Location settings.", false);
        }
    }

    @SuppressWarnings("deprecation")
    public void stopScan() {
        handler.removeCallbacks(stopScanTask);
        handler.removeCallbacks(compatibilityScanTask);
        boolean wasScanning = scanning;
        scanning = false;
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); }
            catch (SecurityException | IllegalStateException ignored) { }
            scanner = null;
        }
        if (legacyScanning) {
            try { if (adapter != null) adapter.stopLeScan(legacyScanCallback); }
            catch (SecurityException | IllegalStateException ignored) { }
            legacyScanning = false;
        }
        if (wasScanning) {
            log("Scan ended; received " + discoveredCount + " BLE advertisements");
            handler.post(listener::onScanFinished);
        }
    }

    public void connect(BluetoothDevice device) {
        stopScan();
        disconnect();
        assembler.reset();
        standardBatteryMode = false;
        writeInProgress = false;
        pendingCommand = -1;
        status("Connecting to " + device.getAddress() + "…", false);
        log("Connecting to " + device.getAddress());
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) status("Bluetooth connection could not start", false);
            else handler.postDelayed(connectionTimeout, 20000);
        } catch (SecurityException | IllegalArgumentException e) {
            log("Connect failed: " + e.getMessage());
            status("Could not connect", false);
        }
    }

    /** Connect directly to a known BLE address when Android advertisement scanning fails. */
    public void connectByAddress(String address) {
        if (!bluetoothEnabled()) {
            status("Turn on Bluetooth, then retry the saved address", false);
            return;
        }
        try {
            String mac = address.trim().toUpperCase(java.util.Locale.US);
            if (Build.VERSION.SDK_INT >= 33 && "0B:23:EB:D7:03:9E".equals(mac)) {
                log("Using Android LE random address type for ECO039E");
                connect(adapter.getRemoteLeDevice(mac, BluetoothDevice.ADDRESS_TYPE_RANDOM));
            } else {
                connect(adapter.getRemoteDevice(mac));
            }
        } catch (IllegalArgumentException | SecurityException exception) {
            log("Invalid address: " + exception.getMessage());
            status("Invalid BLE address. Use XX:XX:XX:XX:XX:XX.", false);
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int result, int newState) {
            if (g != gatt) return;
            if (result == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected. Discovering GATT services.");
                status("Discovering battery services…", false);
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || result != BluetoothGatt.GATT_SUCCESS) {
                log("Disconnected (GATT status " + result + ")");
                handler.post(() -> {
                    if (g == gatt) {
                        ready = false;
                        writer = null;
                        writeInProgress = false;
                        pendingCommand = -1;
                        handler.removeCallbacks(pollTask);
                        g.close();
                        gatt = null;
                        status("Disconnected — tap Scan to reconnect", false);
                    }
                });
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int code) {
            if (g != gatt) return;
            if (code != BluetoothGatt.GATT_SUCCESS) {
                status("Could not discover GATT services: " + code, false);
                return;
            }
            BluetoothGattService service = g.getService(SERVICE);
            if (service == null) {
                log("This device does not expose the supported 0001 service");
                BluetoothGattService standard = g.getService(BATTERY_SERVICE);
                BluetoothGattCharacteristic level = standard == null ? null : standard.getCharacteristic(BATTERY_LEVEL);
                if (level == null) {
                    for (BluetoothGattService detected : g.getServices())
                        log("GATT service: " + detected.getUuid());
                    status("Connected, but device telemetry protocol is not supported yet", false);
                    return;
                }
                standardBatteryMode = true;
                ready = true;
                handler.removeCallbacks(connectionTimeout);
                status("Connected — standard BLE battery level", true);
                g.readCharacteristic(level);
                return;
            }
            writer = service.getCharacteristic(WRITE);
            BluetoothGattCharacteristic notifications = service.getCharacteristic(NOTIFY);
            if (writer == null || notifications == null) {
                status("BMS telemetry characteristics missing", false);
                return;
            }
            if (!g.setCharacteristicNotification(notifications, true)) {
                status("Could not enable BLE notifications", false);
                return;
            }
            BluetoothGattDescriptor cccd = notifications.getDescriptor(CCCD);
            if (cccd == null) {
                status("Notification descriptor missing", false);
                return;
            }
            int result;
            if (Build.VERSION.SDK_INT >= 33) {
                result = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                result = g.writeDescriptor(cccd) ? BluetoothGatt.GATT_SUCCESS : -1;
            }
            if (result != BluetoothGatt.GATT_SUCCESS) status("Notification subscription failed (" + result + ")", false);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int code) {
            if (g != gatt || !CCCD.equals(descriptor.getUuid())) return;
            if (code != BluetoothGatt.GATT_SUCCESS) {
                status("Notification subscription rejected (" + code + ")", false);
                return;
            }
            handler.post(() -> {
                if (g != gatt) return;
                ready = true;
                handler.removeCallbacks(connectionTimeout);
                status("Connected — reading live battery telemetry", true);
                log("Notifications enabled on 0003");
                pollIndex = 0;
                handler.removeCallbacks(pollTask);
                handler.postDelayed(pollTask, 350);
            });
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int statusCode) {
            if (g == gatt && standardBatteryMode && BATTERY_LEVEL.equals(c.getUuid())
                    && statusCode == BluetoothGatt.GATT_SUCCESS) publishBatteryLevel(value);
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            if (Build.VERSION.SDK_INT < 33 && g == gatt && standardBatteryMode
                    && BATTERY_LEVEL.equals(c.getUuid()) && statusCode == BluetoothGatt.GATT_SUCCESS)
                publishBatteryLevel(c.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            processNotification(g, characteristic, value);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33)
                processNotification(g, characteristic, characteristic.getValue());
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g,
                BluetoothGattCharacteristic characteristic, int status) {
            if (g != gatt) return;
            handler.post(() -> {
                writeInProgress = false;
                if (status != BluetoothGatt.GATT_SUCCESS)
                    log("Telemetry request write failed: " + status);
                if (pendingCommand >= 0) {
                    int next = pendingCommand;
                    pendingCommand = -1;
                    handler.postDelayed(() -> request(next), 180);
                }
            });
        }
    };

    private void publishBatteryLevel(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        int percent = bytes[0] & 0xFF;
        if (percent <= 100) handler.post(() -> listener.onBatteryLevel(percent));
    }

    private void processNotification(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] bytes) {
        if (g != gatt || bytes == null) return;
        if (standardBatteryMode && BATTERY_LEVEL.equals(characteristic.getUuid())) {
            publishBatteryLevel(bytes);
            return;
        }
        if (!NOTIFY.equals(characteristic.getUuid())) return;
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        log("RX " + BatteryProtocol.hex(copy));
        List<byte[]> packets = assembler.feed(copy);
        for (byte[] packet : packets) {
            int cmd = packet[1] & 0xFF;
            if (cmd == 0x21) {
                BatteryProtocol.BatteryInfo info = BatteryProtocol.parseInfo(packet);
                if (info != null) handler.post(() -> listener.onInfo(info));
                else log("Unrecognized battery-info packet: " + BatteryProtocol.hex(packet));
            } else if (cmd == 0x22) {
                double[] cells = BatteryProtocol.parseCells(packet);
                if (cells.length > 0) handler.post(() -> listener.onCells(cells));
                else log("Unrecognized cell-voltage packet: " + BatteryProtocol.hex(packet));
            } else {
                log("Unknown telemetry packet 0x" + Integer.toHexString(cmd));
            }
        }
    }

    public boolean isReady() { return ready; }
    public boolean isScanning() { return scanning; }

    public void refresh() {
        if (ready && standardBatteryMode && gatt != null) {
            BluetoothGattService svc = gatt.getService(BATTERY_SERVICE);
            if (svc != null && svc.getCharacteristic(BATTERY_LEVEL) != null)
                gatt.readCharacteristic(svc.getCharacteristic(BATTERY_LEVEL));
        } else if (ready) request(0x21);
        else status("Connect to a BMS before refreshing", false);
    }

    private void request(int code) {
        if (!ready || writer == null || gatt == null) return;
        if (writeInProgress) {
            pendingCommand = code;
            return;
        }
        byte[] payload = BatteryProtocol.request(code);
        try {
            int result;
            if (Build.VERSION.SDK_INT >= 33) {
                result = gatt.writeCharacteristic(writer, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                writer.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                writer.setValue(payload);
                result = gatt.writeCharacteristic(writer) ? BluetoothGatt.GATT_SUCCESS : -1;
            }
            if (result == BluetoothGatt.GATT_SUCCESS) {
                writeInProgress = true;
                log("TX " + BatteryProtocol.hex(payload));
            }
            else log("Could not queue request 0x" + Integer.toHexString(code) + " (" + result + ")");
        } catch (SecurityException | IllegalStateException e) {
            log("Request failed: " + e.getMessage());
        }
    }

    public void disconnect() {
        stopScan();
        handler.removeCallbacks(pollTask);
        handler.removeCallbacks(connectionTimeout);
        ready = false;
        standardBatteryMode = false;
        writer = null;
        writeInProgress = false;
        pendingCommand = -1;
        assembler.reset();
        if (gatt != null) {
            BluetoothGatt old = gatt;
            gatt = null;
            try { old.disconnect(); old.close(); }
            catch (SecurityException ignored) { }
        }
    }

    public void close() {
        disconnect();
        handler.removeCallbacksAndMessages(null);
    }
}
