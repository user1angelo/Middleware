package com.nis1.thesis.udm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip serialize/deserialize tests for MaltrailAlertData, confirming it uses the
 * same snake_case wire format as SuricataAlertData for fields shared between the two
 * (required for WorkflowMatcher's condition evaluation to work unmodified across both
 * alert sources).
 */
class MaltrailAlertDataTest {

    private final Gson gson = new Gson();

    @Test
    void roundTripsAllFields() {
        MaltrailAlertData original = new MaltrailAlertData(
                "MALT-1a2b3c4d", "ransomware", "10.0.0.55", "203.0.113.9", "high", "203.0.113.9");
        original.setSourcePort(51234);
        original.setDestinationPort(443);
        original.setProtocol("tcp");
        original.setCategory("ransomware");
        original.setAlertType("ransomware");
        original.setThreatScore(75);
        original.setConfidenceScore(90);
        original.setReference("abuse.ch");
        original.setSensor("sensor-01");

        String json = gson.toJson(original);
        MaltrailAlertData restored = gson.fromJson(json, MaltrailAlertData.class);

        assertEquals(original.getAlertId(), restored.getAlertId());
        assertEquals(original.getSignature(), restored.getSignature());
        assertEquals(original.getSourceIp(), restored.getSourceIp());
        assertEquals(original.getDestinationIp(), restored.getDestinationIp());
        assertEquals(original.getSourcePort(), restored.getSourcePort());
        assertEquals(original.getDestinationPort(), restored.getDestinationPort());
        assertEquals(original.getProtocol(), restored.getProtocol());
        assertEquals(original.getSeverity(), restored.getSeverity());
        assertEquals(original.getCategory(), restored.getCategory());
        assertEquals(original.getAlertType(), restored.getAlertType());
        assertEquals(original.getThreatScore(), restored.getThreatScore());
        assertEquals(original.getConfidenceScore(), restored.getConfidenceScore());
        assertEquals(original.getTrail(), restored.getTrail());
        assertEquals(original.getReference(), restored.getReference());
        assertEquals(original.getSensor(), restored.getSensor());
    }

    @Test
    void jsonKeysMatchSuricataAlertDataConventionForSharedFields() {
        MaltrailAlertData original = new MaltrailAlertData(
                "MALT-1", "ransomware", "1.1.1.1", "2.2.2.2", "high", "2.2.2.2");
        original.setAlertType("ransomware");
        original.setThreatScore(75);
        String json = gson.toJson(original);

        // Same snake_case keys SuricataAlertData uses for the fields WorkflowMatcher checks
        assertTrue(json.contains("\"severity\""));
        assertTrue(json.contains("\"alert_type\""));
        assertTrue(json.contains("\"threat_score\""));
        assertTrue(json.contains("\"source_ip\""));
    }
}
