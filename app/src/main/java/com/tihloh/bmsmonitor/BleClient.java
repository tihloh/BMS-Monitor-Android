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
        void onLog(String entry);
    }

    private static final UUID SERVICE = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb");
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
    private int discoveredCount;
    private volatile boolean scanning;
    private int pollIndex;
    private boolean writeInProgress;
    private int pendingCommand = -1;

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
            log("Android BLE scan failed: error " + errorCode);
            status("Scan failed (" + errorCode + "). Try Bluetooth off/on and rescan.", false);
            stopScan();
            handler.post(listener::onScanFinished);
        }
    };

    private final Runnable stopScanTask = this::stopScan;
    // Retry with Android's simplest scanner API on vendors returning zero results.
    private final Runnable fallbackScanTask = () -> {
        if (!scanning || scanner == null || discoveredCount > 0) return;
        log("No advertisements; retrying with default BLE scanner parameters");
        try {
            scanner.stopScan(scanCallback);
            scanner.startScan(scanCallback);
        } catch (SecurityException | IllegalStateException e) {
            log("Fallback scanner failed: " + e.getMessage());
            status("Cannot scan. Check Nearby devices and Location.", false);
            stopScan();
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
        if (!bluetoothEnabled()) {
            status("Turn on Bluetooth, then scan again", false);
            handler.post(listener::onScanFinished);
            return;
        }
        stopScan();
        discoveredCount = 0;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("BLE scanner unavailable. Toggle Bluetooth.", false);
            handler.post(listener::onScanFinished);
            return;
        }
        status("Scanning for nearby BLE devices…", false);
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
            scanning = true;
            scanner.startScan(null, settings, scanCallback);
            log("BLE scan started; no device filters; low-latency mode");
            handler.postDelayed(fallbackScanTask, 8500);
            handler.postDelayed(stopScanTask, 19000);
        } catch (SecurityException | IllegalStateException e) {
            scanning = false;
            log("Scan start failed: " + e.getClass().getSimpleName() + " / " + e.getMessage());
            status("Cannot scan. Check Nearby devices + Location permission.", false);
            handler.post(listener::onScanFinished);
        }
    }

    public void stopScan() {
        handler.removeCallbacks(stopScanTask);
        handler.removeCallbacks(fallbackScanTask);
        boolean wasScanning = scanning;
        scanning = false;
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); }
            catch (SecurityException | IllegalStateException ignored) { }
            scanner = null;
        }
        if (wasScanning) {
            log("Scan completed; found " + discoveredCount + " advertisements");
            handler.post(listener::onScanFinished);
        }
    }

    public void connect(BluetoothDevice device) {
        stopScan();
        disconnect();
        assembler.reset();
        writeInProgress = false;
        pendingCommand = -1;
        status("Connecting to " + device.getAddress() + "…", false);
        log("Connecting to " + device.getAddress());
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) status("Bluetooth connection could not start", false);
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
            // Gentai ECO039E advertises a random-type BLE address in the supplied scan.
            // Android 13+ can construct a LE device with the correct address type.
            BluetoothDevice target;
            if (Build.VERSION.SDK_INT >= 33 && "0B:23:EB:D7:03:9E".equals(mac)) {
                target = adapter.getRemoteLeDevice(mac, BluetoothDevice.ADDRESS_TYPE_RANDOM);
                log("Using LE random address type for ECO039E");
            } else {
                target = adapter.getRemoteDevice(mac);
            }
            connect(target);
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
                status("Unsupported BLE device (service 0001 missing)", false);
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
                status("Connected — reading live battery telemetry", true);
                log("Notifications enabled on 0003");
                pollIndex = 0;
                handler.removeCallbacks(pollTask);
                handler.postDelayed(pollTask, 350);
            });
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

    private void processNotification(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] bytes) {
        if (g != gatt || !NOTIFY.equals(characteristic.getUuid()) || bytes == null) return;
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
        if (ready) request(0x21);
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
        ready = false;
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
