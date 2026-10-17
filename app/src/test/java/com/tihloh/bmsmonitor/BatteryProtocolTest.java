package com.tihloh.bmsmonitor;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class BatteryProtocolTest {
    @Test public void decodesCapturedGentaiFragments() {
        byte[] p1 = fromHex("AA211A8665000082FBFFFF1364184C0000A08601");
        byte[] p2 = fromHex("00BF001E19191A1F1A0508");
        BatteryProtocol.Assembler assembler = new BatteryProtocol.Assembler();
        assertEquals(0, assembler.feed(p1).size());
        List<byte[]> packets = assembler.feed(p2);
        assertEquals(1, packets.size());
        BatteryProtocol.BatteryInfo info = BatteryProtocol.parseInfo(packets.get(0));
        assertNotNull(info);
        assertEquals(25.99, info.voltageV, 0.001);
        assertEquals(-1.15, info.currentA, 0.001);
        assertEquals(19, info.socPercent, 0.001);
        assertEquals(19.48, info.remainingAh, 0.001);
        assertEquals(100, info.totalAh, 0.001);
    }

    @Test public void permitsOnlyReadTelemetry() {
        assertEquals("AA21002100", BatteryProtocol.hex(BatteryProtocol.request(0x21)));
        assertEquals("AA22002200", BatteryProtocol.hex(BatteryProtocol.request(0x22)));
        try { BatteryProtocol.request(0x20); fail("Unsafe command was accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
