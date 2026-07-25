package com.nis1.thesis.udm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Documented drop-behavior tests for SuricataModule.parseEveJsonLine, per
 * SDK_CODEBASE_ANSWERS.md item 29: non-alert EVE records are silently ignored; alerts
 * missing alert/src_ip/dest_ip are logged then dropped; malformed JSON is logged then
 * dropped. All three drop paths return before the method ever touches its Channel
 * parameter (publishSuricataAlert is only reached on the success path), so these tests
 * pass a null Channel deliberately - if any drop path regressed to reach the publish
 * call, it would throw a NullPointerException here instead of silently passing.
 */
class SuricataModuleParseTest {

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
    void nonAlertEventTypeIsSilentlyIgnored() {
        String dnsRecord = "{\"event_type\":\"dns\",\"src_ip\":\"1.1.1.1\",\"dest_ip\":\"2.2.2.2\"}";

        assertDoesNotThrow(() -> SuricataModule.parseEveJsonLine(dnsRecord, null));
        assertEquals("", capturedErr.toString(StandardCharsets.UTF_8), "non-alert records should produce no log output at all");
    }

    @Test
    void alertMissingRequiredFieldsIsLoggedAndDropped() {
        String incompleteAlert = "{\"event_type\":\"alert\"}"; // no alert/src_ip/dest_ip

        assertDoesNotThrow(() -> SuricataModule.parseEveJsonLine(incompleteAlert, null));
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Eve.json alert missing required fields"), "expected drop warning in: " + err);
        assertTrue(err.contains("alert:NULL"), "expected alert:NULL marker in: " + err);
    }

    @Test
    void malformedJsonIsLoggedAndDropped() {
        String malformed = "{not valid json}";

        assertDoesNotThrow(() -> SuricataModule.parseEveJsonLine(malformed, null));
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Failed to parse eve.json line"), "expected parse-failure log in: " + err);
    }

    @Test
    void nonBraceDelimitedLineIsSilentlyIgnored() {
        String notJson = "this is not json at all";

        assertDoesNotThrow(() -> SuricataModule.parseEveJsonLine(notJson, null));
        assertEquals("", capturedErr.toString(StandardCharsets.UTF_8), "non-JSON-shaped lines should produce no log output");
    }
}
