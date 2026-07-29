package com.nis1.thesis.sdk;

import org.json.JSONObject;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds the standard alert envelope every standalone module (SuricataModule, MaltrailModule,
 * Fail2banModule, SysmonModule, ...) publishes to {@code workflow_queue}:
 * <pre>{"message_type": "alert", "event_id": ..., "timestamp": ..., "event_type": ...,
 * "source_module": ..., "telemetry": ... (optional), "payload": ...}</pre>
 * <p>
 * Extracted from four independent, duplicated implementations of the same ~15-20 lines - see
 * SDK_USABILITY_AUDIT.md (Abstraction Level / API Elaboration dimensions) for why this existed
 * as copy-pasted boilerplate rather than a shared utility until now.
 */
public final class AlertEnvelopeBuilder {

    private String eventId = UUID.randomUUID().toString();
    private Instant timestamp = Instant.now();
    private String eventType;
    private String sourceModule;
    private JSONObject telemetry;
    private JSONObject payload;

    private AlertEnvelopeBuilder() {
    }

    public static AlertEnvelopeBuilder create() {
        return new AlertEnvelopeBuilder();
    }

    public AlertEnvelopeBuilder eventId(String eventId) {
        this.eventId = eventId;
        return this;
    }

    public AlertEnvelopeBuilder timestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public AlertEnvelopeBuilder eventType(String eventType) {
        this.eventType = eventType;
        return this;
    }

    public AlertEnvelopeBuilder sourceModule(String sourceModule) {
        this.sourceModule = sourceModule;
        return this;
    }

    public AlertEnvelopeBuilder telemetry(JSONObject telemetry) {
        this.telemetry = telemetry;
        return this;
    }

    public AlertEnvelopeBuilder payload(JSONObject payload) {
        this.payload = payload;
        return this;
    }

    public JSONObject build() {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(sourceModule, "sourceModule");
        Objects.requireNonNull(payload, "payload");

        JSONObject alert = new JSONObject();
        alert.put("message_type", "alert");
        alert.put("event_id", eventId);
        alert.put("timestamp", timestamp.toString());
        if (telemetry != null) {
            alert.put("telemetry", telemetry);
        }
        alert.put("event_type", eventType);
        alert.put("source_module", sourceModule);
        alert.put("payload", payload);
        return alert;
    }
}
