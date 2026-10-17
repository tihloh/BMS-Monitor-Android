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
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** BLE central for the known Gentai/ECO telemetry-only GATT profile. */
@SuppressLint("MissingPermission")
public final class BleClient {
    public interface Listener {
        void onStatus(String status, boolean ready);
        void onDevice(BluetoothDevice device, String name, int rssi);
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
    private boolean ready;
    private int pollIndex;
    private int scanCount;
    private int pendingCode = -1;
    private boolean writeInProgress;

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
            if (name == null || name.isEmpty()) name = result.getDevice().getName();
            final String finalName = name == null || name.isEmpty() ? "Unnamed BLE device" : name;
            scanCount++;
            final BluetoothDevice device = result.getDevice();
            final int rssi = result.getRssi();
            handler.post(() -> listener.onDevice(device, finalName, rssi));
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
        }

        @Override public void onScanFailed(int errorCode) {
            log("Scan error: " + errorCode);
            status("Scan error " + errorCode + " — turn Bluetooth off/on and retry", false);
        }
    };

    private final Runnable stopScanTask = () -> {
        if (scanner != null) {
            int found = scanCount;
            stopScan();
            status(found == 0 ? "No devices found — check Bluetooth / Location, then retry"
                    : "Scan finished · " + found + " signal(s) found", false);
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
            return;
        }
        stopScan();
        scanCount = 0;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("BLE scanner unavailable", false);
            return;
        }
        status("Scanning for nearby BLE devices…", false);
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0L)
                    .build();
            scanner.startScan(new ArrayList<>(), settings, scanCallback);
            handler.postDelayed(stopScanTask, 18000);
        } catch (SecurityException | IllegalStateException e) {
            log("Scan start failed: " + e.getMessage());
            status("Cannot start Bluetooth scan", false);
        }
    }

    public void stopScan() {
        handler.removeCallbacks(stopScanTask);
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); }
            catch (SecurityException | IllegalStateException ignored) { }
            scanner = null;
        }
    }

    public void connect(BluetoothDevice device) {
        stopScan();
        disconnect();
        assembler.reset();
        pendingCode = -1;
        writeInProgress = false;
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

    public void connectByAddress(String address) {
        if (!bluetoothEnabled()) {
            status("Turn on Bluetooth before connecting", false);
            return;
        }
        try {
            connect(adapter.getRemoteDevice(address.trim()));
        } catch (IllegalArgumentException | SecurityException error) {
            log("Cannot connect using address: " + error.getMessage());
            status("Invalid Bluetooth address", false);
        }
    }

    public boolean isScanning() { return scanner != null; }

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
                        pendingCode = -1;
                        writeInProgress = false;
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
                if (pendingCode >= 0) {
                    int next = pendingCode;
                    pendingCode = -1;
                    handler.postDelayed(() -> request(next), 200);
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

    public void refresh() {
        if (ready) request(0x21);
        else status("Connect to a BMS before refreshing", false);
    }

    private void request(int code) {
        if (!ready || writer == null || gatt == null) return;
        if (writeInProgress) {
            pendingCode = code;
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
        pendingCode = -1;
        writeInProgress = false;
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
