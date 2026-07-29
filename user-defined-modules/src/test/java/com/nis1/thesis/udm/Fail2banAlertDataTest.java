package com.nis1.thesis.udm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip serialize/deserialize tests for Fail2banAlertData, confirming it uses the same
 * snake_case wire format as SuricataAlertData/MaltrailAlertData for fields WorkflowMatcher checks.
 */
class Fail2banAlertDataTest {

    private final Gson gson = new Gson();

    @Test
    void roundTripsAllFields() {
        Fail2banAlertData original = new Fail2banAlertData(
                "F2B-1a2b3c4d", "SSH brute-force (jail: sshd)", "203.0.113.66", "high", "sshd", "Ban");
        original.setCategory("brute_force");
        original.setAlertType("brute_force");
        original.setThreatScore(75);
        original.setConfidenceScore(92);

        String json = gson.toJson(original);
        Fail2banAlertData restored = gson.fromJson(json, Fail2banAlertData.class);

        assertEquals(original.getAlertId(), restored.getAlertId());
        assertEquals(original.getSignature(), restored.getSignature());
        assertEquals(original.getSourceIp(), restored.getSourceIp());
        assertEquals(original.getSeverity(), restored.getSeverity());
        assertEquals(original.getCategory(), restored.getCategory());
        assertEquals(original.getAlertType(), restored.getAlertType());
        assertEquals(original.getThreatScore(), restored.getThreatScore());
        assertEquals(original.getConfidenceScore(), restored.getConfidenceScore());
        assertEquals(original.getJail(), restored.getJail());
        assertEquals(original.getBanAction(), restored.getBanAction());
    }

    @Test
    void jsonKeysMatchSharedConventionForWorkflowMatcherFields() {
        Fail2banAlertData original = new Fail2banAlertData(
                "F2B-1", "sig", "1.1.1.1", "high", "sshd", "Ban");
        String json = gson.toJson(original);

        assertTrue(json.contains("\"severity\""));
        assertTrue(json.contains("\"source_ip\""));
        assertFalse(json.contains("\"sourceIp\""));
    }
}
