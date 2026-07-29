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
 * Documented drop-behavior tests for Fail2banModule.parseFail2banLine, mirroring
 * SuricataModuleParseTest/MaltrailModuleParseTest's coverage: non-actions lines are silently
 * ignored, malformed actions lines (missing jail/action/ip) are logged then dropped, and Unban
 * events are silently ignored after successful parsing. All drop paths return before the
 * method touches its Channel parameter, so these tests pass a null Channel deliberately.
 */
class Fail2banModuleParseTest {

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
    void nonActionsLineIsSilentlyIgnored() {
        String line = "2026-07-29 18:20:00,000 fail2ban.filter [12345]: INFO Found 203.0.113.66";

        assertDoesNotThrow(() -> Fail2banModule.parseFail2banLine(line, null));
        assertEquals("", capturedErr.toString(StandardCharsets.UTF_8), "non-actions lines should produce no log output");
    }

    @Test
    void malformedActionsLineIsLoggedAndDropped() {
        String line = "2026-07-29 18:20:00,000 fail2ban.actions        [12345]: NOTICE  [sshd] Ban";

        assertDoesNotThrow(() -> Fail2banModule.parseFail2banLine(line, null));
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Fail2ban actions line missing required fields"), "expected drop warning in: " + err);
    }

    @Test
    void unbanLineIsSilentlyIgnoredAfterParsing() {
        String line = "2026-07-29 18:20:00,000 fail2ban.actions        [12345]: NOTICE  [sshd] Unban 203.0.113.66";

        assertDoesNotThrow(() -> Fail2banModule.parseFail2banLine(line, null));
        assertEquals("", capturedErr.toString(StandardCharsets.UTF_8), "Unban events should produce no log output");
    }

    @Test
    void wellFormedBanLineBuildsCorrectlyMappedAlert() {
        String line = "2026-07-29 18:20:00,000 fail2ban.actions        [12345]: NOTICE  [sshd] Ban 203.0.113.66";

        AtomicReference<byte[]> publishedBody = new AtomicReference<>();
        Channel fakeChannel = fakeChannelCapturingBasicPublish(publishedBody);

        assertDoesNotThrow(() -> Fail2banModule.parseFail2banLine(line, fakeChannel));

        assertNotNull(publishedBody.get(), "expected a well-formed Ban line to reach basicPublish");
        JSONObject alert = new JSONObject(new String(publishedBody.get(), StandardCharsets.UTF_8));

        assertEquals("alert", alert.getString("message_type"));
        assertEquals("alerts.host.fail2ban", alert.getString("event_type"));

        JSONObject payload = alert.getJSONObject("payload");
        assertEquals("high", payload.getString("severity"));
        assertEquals("brute_force", payload.getString("category"));
        assertEquals("203.0.113.66", payload.getString("source_ip"));
        assertEquals("sshd", payload.getString("jail"));
        assertTrue(payload.getInt("threat_score") >= 65);
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
                Fail2banModuleParseTest.class.getClassLoader(),
                new Class<?>[]{Channel.class},
                handler);
    }
}
