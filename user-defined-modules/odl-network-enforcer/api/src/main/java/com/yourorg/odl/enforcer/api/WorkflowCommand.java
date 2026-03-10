package com.yourorg.odl.enforcer.api;

import org.json.JSONObject;
import java.time.Instant;

/**
 * Represents a workflow command message from the workflow engine.
 * Follows the same structure as alert/query messages in the middleware.
 */
public class WorkflowCommand {
    private final String messageType;
    private final String eventId;
    private final String timestamp;
    private final String eventType;
    private final String sourceModule;
    private final JSONObject payload;

    public WorkflowCommand(String messageType, String eventId, String timestamp,
                          String eventType, String sourceModule, JSONObject payload) {
        this.messageType = messageType;
        this.eventId = eventId;
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.sourceModule = sourceModule;
        this.payload = payload;
    }

    public static WorkflowCommand fromJson(String jsonString) {
        JSONObject json = new JSONObject(jsonString);
        return new WorkflowCommand(
            json.getString("message_type"),
            json.getString("event_id"),
            json.getString("timestamp"),
            json.getString("event_type"),
            json.getString("source_module"),
            json.getJSONObject("payload")
        );
    }

    public String getMessageType() {
        return messageType;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSourceModule() {
        return sourceModule;
    }

    public JSONObject getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return String.format("WorkflowCommand[type=%s, eventId=%s, eventType=%s]",
                messageType, eventId, eventType);
    }
}
