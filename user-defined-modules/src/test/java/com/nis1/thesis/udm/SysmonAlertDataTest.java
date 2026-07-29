package com.nis1.thesis.udm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip serialize/deserialize tests for SysmonAlertData.
 */
class SysmonAlertDataTest {

    private final Gson gson = new Gson();

    @Test
    void roundTripsAllFields() {
        SysmonAlertData original = new SysmonAlertData(
                "SYSMON-1a2b3c4d", "Ransomware pre-encryption command detected: vssadmin delete shadows",
                "critical", 1, "WIN-HOST01", "vssadmin.exe delete shadows /all /quiet");
        original.setCategory("ransomware");
        original.setAlertType("ransomware");
        original.setThreatScore(90);
        original.setConfidenceScore(95);
        original.setImage("C:\\Windows\\System32\\cmd.exe");
        original.setUser("WIN-HOST01-Administrator");

        String json = gson.toJson(original);
        SysmonAlertData restored = gson.fromJson(json, SysmonAlertData.class);

        assertEquals(original.getAlertId(), restored.getAlertId());
        assertEquals(original.getSignature(), restored.getSignature());
        assertEquals(original.getSeverity(), restored.getSeverity());
        assertEquals(original.getCategory(), restored.getCategory());
        assertEquals(original.getAlertType(), restored.getAlertType());
        assertEquals(original.getThreatScore(), restored.getThreatScore());
        assertEquals(original.getConfidenceScore(), restored.getConfidenceScore());
        assertEquals(original.getSysmonEventId(), restored.getSysmonEventId());
        assertEquals(original.getComputer(), restored.getComputer());
        assertEquals(original.getImage(), restored.getImage());
        assertEquals(original.getCommandLine(), restored.getCommandLine());
        assertEquals(original.getUser(), restored.getUser());
    }

    @Test
    void jsonKeysMatchSharedConventionForWorkflowMatcherFields() {
        SysmonAlertData original = new SysmonAlertData(
                "SYSMON-1", "sig", "critical", 1, "HOST01", "cmd");
        String json = gson.toJson(original);

        assertTrue(json.contains("\"severity\""));
        assertTrue(json.contains("\"sysmon_event_id\""));
        assertFalse(json.contains("\"sysmonEventId\""));
    }
}
