package com.tihloh.bmsmonitor;
import org.junit.Test;
import static org.junit.Assert.*;
public class UpdateClientTest {
    @Test public void comparesVersions() {
        assertTrue(UpdateClient.compare("0.2.1", "0.2.0") > 0);
        assertTrue(UpdateClient.compare("v1.0.0", "0.2.0") > 0);
        assertTrue(UpdateClient.compare("0.1.9", "0.2.0") < 0);
        assertEquals(0, UpdateClient.compare("0.2.0", "0.2.0"));
    }
}
