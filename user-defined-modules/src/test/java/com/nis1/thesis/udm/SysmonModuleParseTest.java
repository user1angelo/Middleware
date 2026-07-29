package com.nis1.thesis.udm;

import com.rabbitmq.client.Channel;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Documented drop-behavior tests for SysmonModule.parseSysmonPacket: malformed UDP payload,
 * missing required fields, and unhandled Sysmon event IDs are all dropped without reaching
 * the Channel. Mirrors SuricataModuleParseTest/MaltrailModuleParseTest/Fail2banModuleParseTest.
 */
class SysmonModuleParseTest {

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
        String incompletePacket = "{\"timestamp\":1753400000}"; // no event_id/computer

        assertDoesNotThrow(() -> SysmonModule.parseSysmonPacket(incompletePacket, null));
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Sysmon event missing required fields"), "expected drop warning in: " + err);
        assertTrue(err.contains("eventId:NULL"));
        assertTrue(err.contains("computer:NULL"));
    }

    @Test
    void malformedJsonPacketIsLoggedAndDropped() {
        String malformed = "{\"unterminated\": \"string}";

        assertDoesNotThrow(() -> SysmonModule.parseSysmonPacket(malformed, null));
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Failed to parse Sysmon UDP packet"), "expected parse-failure log in: " + err);
    }

    @Test
    void nonBraceDelimitedPacketIsSilentlyIgnored() {
        assertDoesNotThrow(() -> SysmonModule.parseSysmonPacket("not json at all", null));
        assertEquals("", capturedErr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unhandledEventIdIsSilentlyIgnored() {
        // event_id 3 = Network Connection - not one of the two IDs this module handles (1, 11)
        String packet = "{\"event_id\":3,\"computer\":\"WIN-HOST01\",\"timestamp\":1753400000}";

        assertDoesNotThrow(() -> SysmonModule.parseSysmonPacket(packet, null));
        assertEquals("", capturedErr.toString(StandardCharsets.UTF_8), "unhandled event IDs should produce no log output");
    }

    @Test
    void wellFormedRansomwarePacketBuildsCorrectlyMappedAlert() {
        String packet = "{"
                + "\"event_id\":1,"
                + "\"computer\":\"WIN-HOST01\","
                + "\"timestamp\":1753400000,"
                + "\"image\":\"C:\\\\Windows\\\\System32\\\\cmd.exe\","
                + "\"command_line\":\"vssadmin.exe delete shadows /all /quiet\","
                + "\"user\":\"WIN-HOST01-Administrator\""
                + "}";

        AtomicReference<byte[]> publishedBody = new AtomicReference<>();
        Channel fakeChannel = fakeChannelCapturingBasicPublish(publishedBody);

        assertDoesNotThrow(() -> SysmonModule.parseSysmonPacket(packet, fakeChannel));

        assertNotNull(publishedBody.get(), "expected a well-formed packet to reach basicPublish");
        JSONObject alert = new JSONObject(new String(publishedBody.get(), StandardCharsets.UTF_8));

        assertEquals("alert", alert.getString("message_type"));
        assertEquals("alerts.host.sysmon", alert.getString("event_type"));

        JSONObject payload = alert.getJSONObject("payload");
        assertEquals("critical", payload.getString("severity"));
        assertEquals("ransomware", payload.getString("category"));
        assertEquals("WIN-HOST01", payload.getString("computer"));
        assertEquals(1, payload.getInt("sysmon_event_id"));
        assertTrue(payload.getInt("threat_score") >= 85);
    }

    @Test
    void wellFormedBenignPacketScoresLow() {
        String packet = "{"
                + "\"event_id\":1,"
                + "\"computer\":\"WIN-HOST01\","
                + "\"timestamp\":1753400000,"
                + "\"image\":\"C:\\\\Windows\\\\System32\\\\notepad.exe\","
                + "\"command_line\":\"notepad.exe report.txt\","
                + "\"user\":\"WIN-HOST01-Administrator\""
                + "}";

        AtomicReference<byte[]> publishedBody = new AtomicReference<>();
        Channel fakeChannel = fakeChannelCapturingBasicPublish(publishedBody);

        assertDoesNotThrow(() -> SysmonModule.parseSysmonPacket(packet, fakeChannel));

        JSONObject alert = new JSONObject(new String(publishedBody.get(), StandardCharsets.UTF_8));
        JSONObject payload = alert.getJSONObject("payload");
        assertEquals("medium", payload.getString("severity"));
        assertEquals("process_creation", payload.getString("category"));
    }

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
                SysmonModuleParseTest.class.getClassLoader(),
                new Class<?>[]{Channel.class},
                handler);
    }
}
