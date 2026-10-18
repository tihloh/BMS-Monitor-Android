package com.tihloh.bmsmonitor;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class JbdProtocolTest {
    @Test public void permitsOnlyReadOnlyJbdCommands() {
        assertEquals("DDA50300FFFD77", BatteryProtocol.hex(JbdProtocol.request(0x03)));
        assertEquals("DDA50400FFFC77", BatteryProtocol.hex(JbdProtocol.request(0x04)));
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
