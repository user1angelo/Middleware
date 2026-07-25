package com.yourorg.workflow;

import com.rabbitmq.client.Channel;
import org.json.JSONArray;
import org.json.JSONObject;

import com.nis1.thesis.sdk.telemetry.StageTimer;

import java.io.IOException;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Executes workflow steps and sends responses to RabbitMQ.
 * Current implementation uses placeholder execution (logs what would be done).
 * 
 * For production, implement actual command execution and integrations.
 */
public class WorkflowExecutor {

    private static final String WORKFLOW_RESPONSE_QUEUE = ConfigLoader.getWorkflowResponseQueueName();
    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * Execute a workflow and send response to RabbitMQ
     */
    public void executeWorkflow(Workflow workflow, JSONObject alert, Channel channel) {
        System.out.println("\n✅ Executing Workflow: " + workflow.getName());
        System.out.println("   Version: " + workflow.getVersion());

        JSONArray stepsExecuted = new JSONArray();

        try {
            // Execute each step
            if (workflow.getSteps() != null) {
                for (int i = 0; i < workflow.getSteps().size(); i++) {
                    Workflow.Step step = workflow.getSteps().get(i);
                    System.out
                            .println("\n   Step " + (i + 1) + "/" + workflow.getSteps().size() + ": " + step.getName());

                    boolean success = executeStep(step, alert, channel);

                    // Record step execution
                    JSONObject stepResult = new JSONObject();
                    stepResult.put("step_name", step.getName());
                    stepResult.put("status", success ? "success" : "failed");
                    stepsExecuted.put(stepResult);
                }
            }

            // Send workflow response
            sendWorkflowResponse(workflow, alert, stepsExecuted, "completed", channel);
            System.out.println("\n✅ Workflow completed: " + workflow.getName());

        } catch (Exception e) {
            System.err.println("\n❌ Workflow failed: " + e.getMessage());

            // Send failure response
            try {
                sendWorkflowResponse(workflow, alert, stepsExecuted, "failed", channel);
            } catch (Exception responseError) {
                System.err.println("⚠️  Failed to send failure response: " + responseError.getMessage());
            }
        }
    }

    /**
     * Execute a single workflow step (placeholder implementation)
     */
    private boolean executeStep(Workflow.Step step, JSONObject alert, Channel channel) {
        try {
            if (step.getAction() == null) {
                System.out.println("      ⚠️  No action defined");
                return false;
            }

            Workflow.Action action = step.getAction();
            System.out.println("      Action Type: " + action.getType());

            if (action.getEvent() == null) {
                System.out.println("      ⚠️  No event defined");
                return false;
            }

            Workflow.Event event = action.getEvent();
            System.out.println("      Event Type: " + event.getType());

            // Substitute template variables in event data
            if (event.getData() != null) {
                System.out.println("      Event Data:");
                for (Map.Entry<String, Object> entry : event.getData().entrySet()) {
                    String value = entry.getValue().toString();

                    // Substitute template variables
                    String substituted = substituteTemplateVariables(value, alert);

                    System.out.println("         " + entry.getKey() + ": " + substituted);
                }
            }

            // Construct command message
            JSONObject command = new JSONObject();

            // Use the workflow event type directly as the command type
            // This must match the module's registered capability (e.g., INITIATE_MITIGATION)
            String commandType = event.getType();

            command.put("event_type", commandType);
            command.put("message_type", "workflow_command");
            command.put("event_id", UUID.randomUUID().toString());
            command.put("timestamp", ZonedDateTime.now(MANILA_ZONE).format(ISO_FORMATTER));
            command.put("source_module", "WorkflowEngine");

            // Propagate the originating alert's trace ID so downstream stages (e.g.
            // CommandRoutingListener's registry_route_dispatch) can be joined back to
            // this alert's consume_deserialize/workflow_load/policy_match/command_dispatch
            // StageTimer rows. The command's own "event_id" above is a freshly generated
            // UUID and cannot be used for that join.
            command.put("trace_id", alert.optString("event_id", "unknown"));

            // Build payload from event data with template variable substitution
            JSONObject payload = new JSONObject();
            if (event.getData() != null) {
                for (Map.Entry<String, Object> entry : event.getData().entrySet()) {
                    String value = entry.getValue().toString();
                    String substituted = substituteTemplateVariables(value, alert);
                    
                    // Pass through all fields with their original names
                    // This preserves workflow field names (targetHost, justification, etc.)
                    // which are expected by SDK modules
                    payload.put(entry.getKey(), substituted);
                }
            }
            // Auto-generate a mitigation_id for INITIATE_MITIGATION commands
            // that don't already carry one. This ensures the same ID flows
            // end-to-end: WorkflowEngine → CommandRoutingListener →
            // SdkModuleHost → ODL module, AND the mirrored copy →
            // webapp dashboard. Without this, the webapp and Java ODL
            // client track the same isolation under different IDs, causing
            // cleanup to miss flows.
            if ("INITIATE_MITIGATION".equals(commandType) && !payload.has("mitigation_id")) {
                payload.put("mitigation_id", "mit-" + UUID.randomUUID().toString());
            }

            // Propagate telemetry
            if (alert.has("telemetry")) {
                JSONObject incomingTelemetry = alert.getJSONObject("telemetry");
                JSONObject telemetry = new JSONObject(incomingTelemetry.toString());
                
                long workflowTimeMillis = System.currentTimeMillis();
                String workflowTimeStr = ZonedDateTime.now(MANILA_ZONE).format(ISO_FORMATTER);
                telemetry.put("workflow_execution_time", workflowTimeStr);
                telemetry.put("workflow_execution_time_ms", workflowTimeMillis);

                long systemReceivedTimeMillis = telemetry.optLong("system_received_time_ms", workflowTimeMillis);
                double receivedToWorkflowDelay = (workflowTimeMillis - systemReceivedTimeMillis) / 1000.0;
                telemetry.put("received_to_workflow_delay_sec", receivedToWorkflowDelay);

                command.put("telemetry", telemetry);
            }

            command.put("payload", payload);

            // Publish to RabbitMQ
            String traceId = alert.optString("event_id", "unknown");
            StageTimer.start(traceId, "command_dispatch");
            String queueName = ConfigLoader.getWorkflowResponseQueueName();
            channel.queueDeclare(queueName, true, false, false, null);
            channel.basicPublish("", queueName, null, command.toString().getBytes("UTF-8"));
            StageTimer.stop(traceId, "command_dispatch");

            System.out.println("      📤 Published command to " + queueName + ": " + command.getString("message_type"));

            return true;

        } catch (Exception e) {
            System.err.println("      ❌ Step failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Substitute template variables with actual alert data
     * Example: "{{ trigger.payload.source_ip }}" → "192.168.1.101"
     */
    private String substituteTemplateVariables(String template, JSONObject alert) {
        if (template == null || !template.contains("{{")) {
            return template;
        }

        String result = template;

        // Find all {{ }} patterns
        while (result.contains("{{")) {
            int start = result.indexOf("{{");
            int end = result.indexOf("}}", start);

            if (end == -1)
                break;

            String variable = result.substring(start + 2, end).trim();
            String value = extractVariableValue(variable, alert);

            result = result.substring(0, start) + value + result.substring(end + 2);
        }

        return result;
    }

    /**
     * Extract value from alert based on variable path
     * Example: "trigger.payload.source_ip" → gets alert.payload.source_ip
     */
    private String extractVariableValue(String variablePath, JSONObject alert) {
        try {
            String originalPath = variablePath;
            // Remove "trigger." prefix if present
            if (variablePath.startsWith("trigger.")) {
                variablePath = variablePath.substring(8);
            }

            // Remap "data." to "payload." if the payload exists but data doesn't
            if (variablePath.startsWith("data.") && !alert.has("data") && alert.has("payload")) {
                variablePath = "payload." + variablePath.substring(5);
            }

            String[] parts = variablePath.split("\\.");
            Object current = alert;

            for (String part : parts) {
                if (current instanceof JSONObject) {
                    JSONObject obj = (JSONObject) current;
                    
                    if (obj.has(part)) {
                        current = obj.get(part);
                    } else {
                        // Fallback checking camelCase or snake_case equivalents
                        boolean found = false;
                        for (String key : obj.keySet()) {
                            if (key.equalsIgnoreCase(part) || 
                                key.replace("_", "").equalsIgnoreCase(part.replace("_", ""))) {
                                current = obj.get(key);
                                found = true;
                                break;
                            }
                        }
                        
                        // Treat empty strings as missing so they don't break string builds
                        if (!found || current == null) {
                            return "[MISSING: " + originalPath + "]";
                        }
                    }
                } else {
                    return current.toString();
                }
            }

            return current != null ? current.toString() : "[MISSING: " + originalPath + "]";

        } catch (Exception e) {
            return "[ERROR: " + variablePath + "]";
        }
    }

    /**
     * Send workflow response to RabbitMQ
     */
    private void sendWorkflowResponse(Workflow workflow, JSONObject alert, JSONArray stepsExecuted,
            String status, Channel channel) throws Exception {

        JSONObject response = new JSONObject();
        response.put("message_type", "workflow_response");
        response.put("event_id", "workflow-" + UUID.randomUUID().toString());
        response.put("original_alert_id", alert.optString("event_id", "unknown"));
        response.put("workflow_name", workflow.getName());
        response.put("workflow_version", workflow.getVersion());
        response.put("status", status);
        response.put("steps_executed", stepsExecuted);
        response.put("timestamp", ZonedDateTime.now(MANILA_ZONE).format(ISO_FORMATTER));

        // Send to workflow_response_queue
        channel.basicPublish("", WORKFLOW_RESPONSE_QUEUE, null, response.toString().getBytes("UTF-8"));

        System.out.println("   📤 Sent workflow response to: " + WORKFLOW_RESPONSE_QUEUE);
    }
}
