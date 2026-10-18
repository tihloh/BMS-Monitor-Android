package com.tihloh.bmsmonitor;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class JbdProtocolTest {
    @Test public void permitsOnlyReadOnlyJbdCommands() {
        assertEquals("DDA50300FFFD77", BatteryProtocol.hex(JbdProtocol.request(0x03)));
        assertEquals("DDA50400FFFC77", BatteryProtocol.hex(JbdProtocol.request(0x04)));
        assertEquals("DDA50500FFFB77", BatteryProtocol.hex(JbdProtocol.request(0x05)));
        try { JbdProtocol.request(0xe1); fail("JBD write command must never be allowed"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void parsesRealJbdBasicInfoExample() {
        // Public JBD ESPHome captured frame, 15.47 V, 4.93 Ah remaining, 5 Ah total, SOC 99%.
        byte[] frame = hex("DD03001D060B000001ED01F400002C7C00000000100080630204030BA00B9D0B98FA5577");
        assertTrue(JbdProtocol.valid(frame, 0x03));
        BatteryProtocol.BatteryInfo info = JbdProtocol.parseInfo(frame);
        assertNotNull(info);
        assertEquals(15.47, info.voltageV, 0.001);
        assertEquals(0, info.currentA, 0.001);
        assertEquals(4.93, info.remainingAh, 0.001);
        assertEquals(5.00, info.totalAh, 0.001);
        assertEquals(99, info.socPercent, 0.001);
    }

    @Test public void combinesFragmentsAndRejectsBadChecksums() {
        byte[] frame = hex("DD03001D060B000001ED01F400002C7C00000000100080630204030BA00B9D0B98FA5577");
        JbdProtocol.Assembler stream = new JbdProtocol.Assembler();
        assertEquals(0, stream.feed(Arrays.copyOfRange(frame, 0, 17)).size());
        List<byte[]> packets = stream.feed(Arrays.copyOfRange(frame, 17, frame.length));
        assertEquals(1, packets.size());
        assertArrayEquals(frame, packets.get(0));
        frame[7] ^= 1;
        assertFalse(JbdProtocol.valid(frame, 0x03));
        stream.reset();
        assertTrue(stream.feed(frame).isEmpty());
    }

    @Test public void decodesSignedCurrentAndCellVoltages() {
        byte[] infoPayload = new byte[20];
        infoPayload[0] = 0x0a;
        infoPayload[1] = 0x28;  // 26.00 volts
        infoPayload[2] = (byte) 0xff;
        infoPayload[3] = (byte) 0x85; // -1.23 amperes
        infoPayload[4] = 0x03; infoPayload[5] = (byte) 0xe8; // 10 Ah remaining
        infoPayload[6] = 0x27; infoPayload[7] = 0x10; // 100 Ah total
        infoPayload[19] = 10;
        BatteryProtocol.BatteryInfo info = JbdProtocol.parseInfo(response(3, infoPayload));
        assertNotNull(info);
        assertEquals(26.0, info.voltageV, 0.001);
        assertEquals(-1.23, info.currentA, 0.001);
        assertEquals(10, info.socPercent, 0.001);
        double[] cells = JbdProtocol.parseCells(response(4, hex("0CC80CCA0CC7")));
        assertArrayEquals(new double[]{3.272, 3.274, 3.271}, cells, 0.001);
    }

    @Test public void parsesDocumentedExtendedJbdTelemetry() {
        byte[] frame = hex("DD03001D060B000001ED01F400002C7C00000000100080630204030BA00B9D0B98FA5577");
        JbdProtocol.Details d = JbdProtocol.parseDetails(frame);
        assertNotNull(d);
        assertEquals(0, d.cycleCount);
        assertEquals("2022-03-28", d.manufactureDate);
        assertEquals("8.0", d.softwareVersion);
        assertEquals(4, d.cellCount);
        assertFalse(d.chargeMosOn);
        assertTrue(d.dischargeMosOn);
        assertEquals(0L, d.balancingMask);
        assertEquals(0x1000, d.protectionFlags);
        assertEquals(3, d.temperaturesC.length);
        assertEquals(24.45, d.temperaturesC[0], 0.01);
        assertEquals(24.15, d.temperaturesC[1], 0.01);
        assertEquals(23.65, d.temperaturesC[2], 0.01);
        assertTrue(d.protectionSummary().contains("FET configuration lock"));
    }

    @Test public void decodesBalancingAndActiveProtectionAndMos() {
        byte[] payload = Arrays.copyOfRange(
            hex("DD03001D060B000001ED01F400002C7C00000000100080630204030BA00B9D0B98FA5577"),
            4, 33);
        payload[12] = 0; payload[13] = 0x05; // Cells 1 and 3 balancing.
        payload[16] = 0x04; payload[17] = 0x01; // Short circuit, cell overvolt.
        payload[20] = 0x03; // Both MOSFETs conducting.
        JbdProtocol.Details d = JbdProtocol.parseDetails(response(3, payload));
        assertNotNull(d);
        assertTrue(d.chargeMosOn);
        assertTrue(d.dischargeMosOn);
        assertEquals(5, d.balancingMask);
        assertEquals("Cell 1, 3", d.activeBalancingCells());
        assertTrue(d.protectionSummary().contains("Short circuit"));
        assertTrue(d.protectionSummary().contains("Cell overvoltage"));
        payload[22] = 4; // Reports four NTC sensors but contains three.
        assertNull(JbdProtocol.parseDetails(response(3, payload)));
    }

    @Test public void readsOnlySupportedIdentityFormat() {
        byte[] name = new byte[] {3, 'J', 'B', 'D'};
        assertEquals("JBD", JbdProtocol.parseDeviceName(response(5, name)));
        assertNull(JbdProtocol.parseDeviceName(response(5, new byte[] {10, 'J'})));
    }

    private static byte[] response(int command, byte[] payload) {
        byte[] result = new byte[payload.length + 7];
        result[0] = (byte) 0xdd; result[1] = (byte) command;
        result[2] = 0; result[3] = (byte) payload.length;
        System.arraycopy(payload, 0, result, 4, payload.length);
        int sum = payload.length;
        for (byte b : payload) sum += b & 0xff;
        int cs = (-sum) & 0xffff;
        result[result.length - 3] = (byte) (cs >>> 8);
        result[result.length - 2] = (byte) cs;
        result[result.length - 1] = 0x77;
        return result;
    }

    private static byte[] hex(String h) {
        byte[] b = new byte[h.length()/2];
        for (int i=0;i<b.length;i++) b[i] = (byte) Integer.parseInt(h.substring(i*2,i*2+2),16);
        return b;
    }
}
