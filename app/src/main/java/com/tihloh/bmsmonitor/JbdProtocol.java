package com.tihloh.bmsmonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Read-only JBD / Jiabaida / Xiaoxiang UART-over-BLE protocol (0xFF00 GATT). */
public final class JbdProtocol {
    private JbdProtocol() { }

    /** Only read-only commands are supported; no MOS, settings, or balancing writes. */
    public static byte[] request(int command) {
        if (command != 0x03 && command != 0x04 && command != 0x05)
            throw new IllegalArgumentException("Only documented JBD read-only registers 03/04/05 may be read");
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

    /** Additional fields documented in the standard JBD/Xiaoxiang basic-info register. */
    public static final class Details {
        public final int cycleCount;
        public final String manufactureDate;
        public final String softwareVersion;
        public final int cellCount;
        public final boolean chargeMosOn, dischargeMosOn;
        public final long balancingMask;
        public final int protectionFlags;
        public final double[] temperaturesC;

        private Details(int cycles, String manufactureDate, String firmware, int cells,
                        boolean chargeMos, boolean dischargeMos, long balance, int faults,
                        double[] temperatures) {
            this.cycleCount = cycles;
            this.manufactureDate = manufactureDate;
            this.softwareVersion = firmware;
            this.cellCount = cells;
            this.chargeMosOn = chargeMos;
            this.dischargeMosOn = dischargeMos;
            this.balancingMask = balance;
            this.protectionFlags = faults;
            this.temperaturesC = Arrays.copyOf(temperatures, temperatures.length);
        }

        public String activeBalancingCells() {
            if (balancingMask == 0) return "None active";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(32, cellCount); i++) {
                if ((balancingMask & (1L << i)) != 0) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(i + 1);
                }
            }
            return sb.length() == 0 ? "Balancing flags outside reported cell count" : "Cell " + sb;
        }

        public String protectionSummary() {
            if (protectionFlags == 0) return "No active protection flags";
            String[] labels = {
                    "Cell overvoltage", "Cell undervoltage",
                    "Pack overvoltage", "Pack undervoltage",
                    "Charge overtemperature", "Charge undertemperature",
                    "Discharge overtemperature", "Discharge undertemperature",
                    "Charge overcurrent", "Discharge overcurrent",
                    "Short circuit", "Analog front-end fault",
                    "FET configuration lock", "Protection bit 13",
                    "Protection bit 14", "Protection bit 15"
            };
            StringBuilder sb = new StringBuilder();
            for (int bit = 0; bit < 16; bit++) {
                if ((protectionFlags & (1 << bit)) != 0) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(labels[bit]);
                }
            }
            return sb.toString();
        }
    }

    /** 03: cycles, MOS states, protection/balancing flags and NTC temperatures. */
    public static Details parseDetails(byte[] frame) {
        if (!valid(frame, 0x03)) return null;
        int length = frame[3] & 0xff;
        if (length < 23) return null;
        int cells = frame[4 + 21] & 0xff;
        int count = frame[4 + 22] & 0xff;
        if (cells < 1 || cells > 32 || count > 16 || length < 23 + count * 2)
            return null;
        int cycles = u16(frame, 4 + 8);
        int date = u16(frame, 4 + 10);
        int year = 2000 + ((date >>> 9) & 0x7f);
        int month = (date >>> 5) & 0x0f;
        int day = date & 0x1f;
        String manufactured = month >= 1 && month <= 12 && day >= 1 && day <= 31
                ? String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
                : "Unavailable";
        int firmware = frame[4 + 18] & 0xff;
        String version = String.format(Locale.US, "%d.%d", firmware >>> 4, firmware & 0xf);
        long balance = (u16(frame, 4 + 12) & 0xffffL)
                | ((long) u16(frame, 4 + 14) << 16);
        int faults = u16(frame, 4 + 16);
        int mos = frame[4 + 20] & 0xff;
        double[] temps = new double[count];
        for (int i = 0; i < count; i++) {
            int raw = u16(frame, 4 + 23 + 2 * i);
            double c = raw / 10.0 - 273.15;
            if (raw < 1800 || raw > 4000) return null;
            temps[i] = c;
        }
        return new Details(cycles, manufactured, version, cells,
                (mos & 1) != 0, (mos & 2) != 0, balance, faults, temps);
    }

    /** Register 05 is often a length-prefixed device-name string; variant responses are ignored. */
    public static String parseDeviceName(byte[] frame) {
        if (!valid(frame, 0x05)) return null;
        int len = frame[3] & 0xff;
        if (len < 2) return null;
        // Common response formats are either length-prefixed or plain ASCII.
        int leading = frame[4] & 0xff;
        int offset = leading >= 1 && leading == len - 1 ? 5 : 4;
        int stringLength = offset == 5 ? leading : len;
        if (stringLength < 1 || stringLength > 48) return null;
        for (int i = offset; i < offset + stringLength; i++) {
            int value = frame[i] & 0xff;
            if (value < 32 || value > 126) return null;
        }
        String name = new String(frame, offset, stringLength,
                java.nio.charset.StandardCharsets.US_ASCII).trim();
        return name.isEmpty() ? null : name;
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
