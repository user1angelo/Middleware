package com.nis1.thesis.udm;

import com.rabbitmq.client.Channel;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Equivalent malformed-input handling tests for MaltrailModule.parseMaltrailPacket
 * (malformed UDP payload, missing required fields), mirroring SuricataModuleParseTest's
 * coverage for the ingestion path this module adds. Both drop paths return before the
 * method ever touches its Channel parameter (publishMaltrailAlert is only reached on the
 * success path), so these tests pass a null Channel deliberately.
 */
class MaltrailModuleParseTest {

    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void redirectStderr() {
        originalErr = System.err;
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
    }

    @Test
    void packetMissingRequiredFieldsIsLoggedAndDropped() {
        String incompletePacket = "{\"timestamp\":1753400000,\"severity\":\"high\"}"; // no src_ip/dst_ip

        assertDoesNotThrow(() -> MaltrailModule.parseMaltrailPacket(incompletePacket, null));
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Maltrail event missing required fields"), "expected drop warning in: " + err);
        assertTrue(err.contains("srcIp:NULL"), "expected srcIp:NULL marker in: " + err);
        assertTrue(err.contains("dstIp:NULL"), "expected dstIp:NULL marker in: " + err);
    }

    @Test
    void malformedJsonPacketIsLoggedAndDropped() {
        String malformed = "{\"unterminated\": \"string}";

        assertDoesNotThrow(() -> MaltrailModule.parseMaltrailPacket(malformed, null));
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Failed to parse Maltrail UDP packet"), "expected parse-failure log in: " + err);
    }

    @Test
    void nonBraceDelimitedPacketIsSilentlyIgnored() {
        String notJson = "this is not json at all";

        assertDoesNotThrow(() -> MaltrailModule.parseMaltrailPacket(notJson, null));
        assertEquals("", capturedErr.toString(StandardCharsets.UTF_8), "non-JSON-shaped packets should produce no log output");
    }

    @Test
    void wellFormedPacketBuildsCorrectlyMappedAlert() {
        String packet = "{"
                + "\"timestamp\":1753400000,"
                + "\"sensor\":\"sensor-01\","
                + "\"severity\":\"high\","
                + "\"src_ip\":\"10.0.0.55\","
                + "\"src_port\":51234,"
                + "\"dst_ip\":\"203.0.113.9\","
                + "\"dst_port\":443,"
                + "\"proto\":\"tcp\","
                + "\"type\":\"ip\","
                + "\"trail\":\"203.0.113.9\","
                + "\"info\":\"ransomware\","
                + "\"reference\":\"abuse.ch\""
                + "}";

        AtomicReference<byte[]> publishedBody = new AtomicReference<>();
        Channel fakeChannel = fakeChannelCapturingBasicPublish(publishedBody);

        assertDoesNotThrow(() -> MaltrailModule.parseMaltrailPacket(packet, fakeChannel));

        assertNotNull(publishedBody.get(), "expected a well-formed packet to reach basicPublish");
        JSONObject alert = new JSONObject(new String(publishedBody.get(), StandardCharsets.UTF_8));

        assertEquals("alert", alert.getString("message_type"));
        assertEquals("alerts.network.maltrail", alert.getString("event_type"));

        JSONObject payload = alert.getJSONObject("payload");
        assertEquals("high", payload.getString("severity"));
        assertEquals("ransomware", payload.getString("category"));
        assertEquals("ransomware", payload.getString("alert_type"));
        assertEquals("ransomware", payload.getString("signature"));
        assertEquals("10.0.0.55", payload.getString("source_ip"));
        assertEquals("203.0.113.9", payload.getString("destination_ip"));
        assertEquals("203.0.113.9", payload.getString("trail"));
        assertEquals(90, payload.getInt("confidence_score"));
        assertTrue(payload.getInt("threat_score") >= 65, "high-severity ransomware should score >= 65");
    }

    /**
     * Minimal dynamic-proxy Channel double: records the byte[] passed to basicPublish and
     * otherwise returns default values. Avoids hand-implementing every method of the large
     * com.rabbitmq.client.Channel interface just to observe one call.
     */
    private static Channel fakeChannelCapturingBasicPublish(AtomicReference<byte[]> capturedBody) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("basicPublish") && args != null && args.length == 4) {
                capturedBody.set((byte[]) args[3]);
                return null;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType.isPrimitive() && returnType != void.class) return 0;
            return null;
        };
        return (Channel) Proxy.newProxyInstance(
                MaltrailModuleParseTest.class.getClassLoader(),
                new Class<?>[]{Channel.class},
                handler);
    }
}
