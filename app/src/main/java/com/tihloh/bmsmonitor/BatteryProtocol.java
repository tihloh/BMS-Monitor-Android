package com.tihloh.bmsmonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Parser for the experimentally confirmed ECO/Gentai BLE telemetry framing. */
public final class BatteryProtocol {
    private BatteryProtocol() { }

    public static byte[] request(int command) {
        if (command != 0x21 && command != 0x22) {
            throw new IllegalArgumentException("Only read-only telemetry commands are allowed");
        }
        return new byte[] {(byte) 0xAA, (byte) command, 0, (byte) command, 0};
    }

    public static final class BatteryInfo {
        public final double voltageV;
        public final double currentA;
        public final double socPercent;
        public final double remainingAh;
        public final double totalAh;
        public final double powerW;

        BatteryInfo(double v, double a, double soc, double remaining, double total) {
            voltageV = v;
            currentA = a;
            socPercent = soc;
            remainingAh = remaining;
            totalAh = total;
            powerW = v * a;
        }
    }

    public static BatteryInfo parseInfo(byte[] frame) {
        if (!valid(frame, 0x21) || frame.length < 23) return null;
        int mv = u16(frame, 3);
        int ma = i32(frame, 7);
        int soc = frame[11] & 0xFF;
        long remaining = u32(frame, 13);
        long total = u32(frame, 17);
        // Reject unreasonable frames instead of displaying nonsensical readings.
        if (mv < 1000 || mv > 100000 || Math.abs((long) ma) > 1000000
                || soc > 100 || total == 0 || total > 10000000
                || remaining > total * 2L) return null;
        return new BatteryInfo(mv / 1000.0, ma / 1000.0, soc,
                remaining / 1000.0, total / 1000.0);
    }

    public static double[] parseCells(byte[] frame) {
        if (!valid(frame, 0x22)) return new double[0];
        List<Double> result = new ArrayList<>();
        // 0x22 cell data consists of little-endian millivolt values after the header.
        // The length field includes trailing metadata, so stop at the first invalid cell.
        int end = Math.min(frame.length - 2, 3 + (frame[2] & 0xFF));
        for (int p = 3; p + 1 < end && result.size() < 32; p += 2) {
            int mv = u16(frame, p);
            if (mv < 1000 || mv > 5000) break;
            result.add(mv / 1000.0);
        }
        double[] values = new double[result.size()];
        for (int i = 0; i < values.length; i++) values[i] = result.get(i);
        return values;
    }

    public static boolean valid(byte[] frame, int type) {
        return frame != null && frame.length >= 5
                && (frame[0] & 0xFF) == 0xAA
                && (frame[1] & 0xFF) == type
                && frame.length == (frame[2] & 0xFF) + 5;
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.US, "%02X", b & 0xFF));
        return out.toString();
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
    private static int i32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }
    private static long u32(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF)
                | (((long) data[offset + 1] & 0xFF) << 8)
                | (((long) data[offset + 2] & 0xFF) << 16)
                | (((long) data[offset + 3] & 0xFF) << 24);
    }

    /** Reassembles length-prefixed BLE packets fragmented across multiple notifications. */
    public static final class Assembler {
        private final byte[] buffer = new byte[512];
        private int size = 0;
        private long lastAppendMs = 0;

        public synchronized List<byte[]> feed(byte[] part) {
            List<byte[]> results = new ArrayList<>();
            if (part == null || part.length == 0) return results;
            long now = System.currentTimeMillis();
            if (lastAppendMs > 0 && now - lastAppendMs > 3000) size = 0;
            lastAppendMs = now;
            for (byte value : part) {
                if (size >= buffer.length) size = 0;
                buffer[size++] = value;
                while (size > 0) {
                    if ((buffer[0] & 0xFF) != 0xAA) { drop(1); continue; }
                    if (size < 3) break;
                    int expected = (buffer[2] & 0xFF) + 5;
                    if (expected < 5 || expected > 200) { drop(1); continue; }
                    if (size < expected) break;
                    results.add(Arrays.copyOf(buffer, expected));
                    drop(expected);
                }
            }
            return results;
        }

        public synchronized void reset() {
            size = 0;
            lastAppendMs = 0;
        }

        private void drop(int len) {
            size -= len;
            if (size > 0) System.arraycopy(buffer, len, buffer, 0, size);
        }
    }
}
