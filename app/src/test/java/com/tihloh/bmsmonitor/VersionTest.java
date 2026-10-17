package com.tihloh.bmsmonitor;
import org.junit.Test;
import static org.junit.Assert.*;
public class VersionTest {
    @Test public void comparesVersionsCorrectly() {
        assertTrue(UpdateManager.newer("0.2.0","0.1.0"));
        assertTrue(UpdateManager.newer("0.2.10","0.2.9"));
        assertFalse(UpdateManager.newer("0.2.0","0.2.0"));
        assertFalse(UpdateManager.newer("0.1.9","0.2.0"));
    }
}
