package com.tihloh.bmsmonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Read-only JBD / Jiabaida / Xiaoxiang UART-over-BLE protocol (0xFF00 GATT). */
public final class JbdProtocol {
    private JbdProtocol() { }

    /** Only read-only commands are supported; no MOS, settings, or balancing writes. */
    public static byte[] request(int command) {
        if (command != 0x03 && command != 0x04)
            throw new IllegalArgumentException("Only JBD telemetry registers 03/04 may be read");
        int check = (-command) & 0xffff;
        return new byte[] {(byte) 0xdd, (byte) 0xa5, (byte) command, 0,
                (byte) (check >>> 8), (byte) check, 0x77};
    }

    /** RX checksum covers status, length, and payload, NOT register number. */
    public static boolean valid(byte[] frame, int register) {
        if (frame == null || frame.length < 7 || (frame[0] & 0xff) != 0xdd ||
                (frame[1] & 0xff) != register || (frame[2] & 0xff) != 0 ||
                (frame[3] & 0xff) + 7 != frame.length ||
                (frame[frame.length - 1] & 0xff) != 0x77) return false;
        int sum = 0;
        for (int i = 2; i < frame.length - 3; i++) sum += frame[i] & 0xff;
        int expected = (-sum) & 0xffff;
        int actual = ((frame[frame.length - 3] & 0xff) << 8) |
                (frame[frame.length - 2] & 0xff);
        return actual == expected;
    }

    /** 03: voltage/current in 0.01 units, capacity in 0.01 Ah, SOC at byte 19. */
    public static BatteryProtocol.BatteryInfo parseInfo(byte[] frame) {
        if (!valid(frame, 0x03) || (frame[3] & 0xff) < 20) return null;
        int voltage = u16(frame, 4);
        int current = (short) u16(frame, 6);
        int remaining = u16(frame, 8);
        int total = u16(frame, 10);
        int soc = frame[4 + 19] & 0xff;
        if (voltage < 100 || voltage > 20000 || Math.abs(current) > 30000 ||
                soc > 100 || total == 0 || remaining > total * 2L) return null;
        return new BatteryProtocol.BatteryInfo(voltage / 100.0, current / 100.0,
                soc, remaining / 100.0, total / 100.0);
    }

    /** 04: each adjacent big-endian pair encodes cell voltage in millivolts. */
    public static double[] parseCells(byte[] frame) {
        if (!valid(frame, 0x04)) return new double[0];
        int size = frame[3] & 0xff;
        if (size == 0 || (size & 1) != 0 || size > 128) return new double[0];
        double[] cells = new double[size / 2];
        for (int i = 0; i < cells.length; i++) {
            int mv = u16(frame, 4 + i * 2);
            if (mv < 1000 || mv > 5000) return new double[0];
            cells[i] = mv / 1000.0;
        }
        return cells;
    }

    private static int u16(byte[] bytes, int pos) {
        return ((bytes[pos] & 0xff) << 8) | (bytes[pos + 1] & 0xff);
    }

    /** Reassembles notifications, discarding bad checksums and resyncing after noise. */
    public static final class Assembler {
        private byte[] buffer = new byte[1024];
        private int size;
        private long lastAppendMs;

        public synchronized void reset() { size = 0; lastAppendMs = 0; }

        public synchronized List<byte[]> feed(byte[] incoming) {
            List<byte[]> packets = new ArrayList<>();
            if (incoming == null || incoming.length == 0) return packets;
            long now = System.currentTimeMillis();
            if (now - lastAppendMs > 5000) size = 0;
            lastAppendMs = now;
            if (incoming.length > buffer.length) { size = 0; return packets; }
            if (size + incoming.length > buffer.length) size = 0;
            System.arraycopy(incoming, 0, buffer, size, incoming.length);
            size += incoming.length;
            while (size >= 7) {
                if ((buffer[0] & 0xff) != 0xdd) { drop(1); continue; }
                int dataLength = buffer[3] & 0xff;
                int length = dataLength + 7;
                if (length > buffer.length) { drop(1); continue; }
                if (size < length) break;
                byte[] candidate = Arrays.copyOf(buffer, length);
                if (valid(candidate, candidate[1] & 0xff)) packets.add(candidate);
                drop(valid(candidate, candidate[1] & 0xff) ? length : 1);
            }
            return packets;
        }

        private void drop(int count) {
            size -= count;
            if (size > 0) System.arraycopy(buffer, count, buffer, 0, size);
        }
    }
}
