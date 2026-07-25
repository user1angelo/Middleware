package com.nis1.thesis.udm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip serialize/deserialize tests for SuricataAlertData, confirming the real
 * snake_case @SerializedName wire format Gson actually emits (not the stale camelCase
 * example previously documented in SURICATA_MODULE_README.md).
 */
class SuricataAlertDataTest {

    private final Gson gson = new Gson();

    @Test
    void roundTripsAllFields() {
        SuricataAlertData original = new SuricataAlertData(
                "SURI-1731898155000-1234", "2024123", "ET MALWARE Suspicious Outbound Connection",
                "192.168.1.100", "203.0.113.50", "high");
        original.setSourcePort(54321);
        original.setDestinationPort(443);
        original.setProtocol("TCP");
        original.setCategory("malware");
        original.setAlertType("malware");
        original.setThreatScore(70);
        original.setConfidenceScore(95);
        original.setAction("allowed");
        original.setFlowId("123456789");
        original.setPacketCount(42L);
        original.setByteCount(1024L);

        String json = gson.toJson(original);
        SuricataAlertData restored = gson.fromJson(json, SuricataAlertData.class);

        assertEquals(original.getAlertId(), restored.getAlertId());
        assertEquals(original.getSignatureId(), restored.getSignatureId());
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
        assertEquals(original.getAction(), restored.getAction());
        assertEquals(original.getFlowId(), restored.getFlowId());
        assertEquals(original.getPacketCount(), restored.getPacketCount());
        assertEquals(original.getByteCount(), restored.getByteCount());
    }

    @Test
    void jsonKeysAreSnakeCaseAsDeclaredBySerializedName() {
        SuricataAlertData original = new SuricataAlertData(
                "SURI-1", "1", "sig", "1.1.1.1", "2.2.2.2", "high");
        String json = gson.toJson(original);

        assertTrue(json.contains("\"alert_id\""), "expected snake_case key alert_id in: " + json);
        assertTrue(json.contains("\"source_ip\""), "expected snake_case key source_ip in: " + json);
        assertFalse(json.contains("\"alertId\""), "did not expect camelCase key in: " + json);
        assertFalse(json.contains("\"sourceIp\""), "did not expect camelCase key in: " + json);
    }
}
