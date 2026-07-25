package com.nis1.thesis.sdk;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip serialize/deserialize tests for NidsAlertData. Unlike SuricataAlertData/
 * MaltrailAlertData, this class has no @SerializedName annotations, so Gson's default
 * field-name mapping applies - JSON keys are the exact camelCase Java field names
 * (e.g. "sourceIp", not "source_ip").
 */
class NidsAlertDataTest {

    private final Gson gson = new Gson();

    @Test
    void roundTripsAllFields() {
        NidsAlertData original = new NidsAlertData("10.0.0.1", "10.0.0.2", "ET SCAN", "high");
        original.setSourcePort(1337);
        original.setDestinationPort(445);
        original.setProtocol("TCP");
        original.setCategory("reconnaissance");
        original.setHostTag("host-01");

        String json = gson.toJson(original);
        NidsAlertData restored = gson.fromJson(json, NidsAlertData.class);

        assertEquals(original.getSourceIp(), restored.getSourceIp());
        assertEquals(original.getDestinationIp(), restored.getDestinationIp());
        assertEquals(original.getSourcePort(), restored.getSourcePort());
        assertEquals(original.getDestinationPort(), restored.getDestinationPort());
        assertEquals(original.getProtocol(), restored.getProtocol());
        assertEquals(original.getSignature(), restored.getSignature());
        assertEquals(original.getSignatureSeverity(), restored.getSignatureSeverity());
        assertEquals(original.getCategory(), restored.getCategory());
        assertEquals(original.getHostTag(), restored.getHostTag());
    }

    @Test
    void jsonKeysAreCamelCaseNotSnakeCase() {
        NidsAlertData original = new NidsAlertData("10.0.0.1", "10.0.0.2", "ET SCAN", "high");
        String json = gson.toJson(original);

        assertTrue(json.contains("\"sourceIp\""), "expected camelCase key sourceIp in: " + json);
        assertFalse(json.contains("\"source_ip\""), "did not expect snake_case key in: " + json);
    }

    @Test
    void noArgConstructorLeavesFieldsNull() {
        NidsAlertData empty = new NidsAlertData();
        assertNull(empty.getSourceIp());
        assertNull(empty.getSignature());
    }
}
