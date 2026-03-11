package com.yourorg.workflow;

import com.rabbitmq.client.*;
import org.json.JSONObject;

import java.util.List;

/**
 * Listens to workflow_queue and processes incoming alerts.
 * Loads workflows, matches them against alerts, and executes matched workflows.
 */
public class WorkflowQueueListener {
    
    private static final String WORKFLOW_QUEUE = ConfigLoader.getWorkflowQueueName();
    private static final String WORKFLOW_RESPONSE_QUEUE = ConfigLoader.getWorkflowResponseQueueName();
    private static final String RABBITMQ_HOST = ConfigLoader.getRabbitMqHost();
    private static final int RABBITMQ_PORT = ConfigLoader.getRabbitMqPort();
    private static final String RABBITMQ_USER = ConfigLoader.getRabbitMqUser();
    private static final String RABBITMQ_PASSWORD = ConfigLoader.getRabbitMqPassword();
    
    private Connection connection;
    private Channel channel;
    private int messageCounter = 0;
    
    private final WorkflowLoader workflowLoader = new WorkflowLoader();
    private final WorkflowMatcher workflowMatcher = new WorkflowMatcher();
    private final WorkflowExecutor workflowExecutor = new WorkflowExecutor();
    
    /**
     * Start listening to workflow_queue
     */
    public void start() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);
        
        connection = factory.newConnection();
        channel = connection.createChannel();
        
        // Declare queues
        channel.queueDeclare(WORKFLOW_QUEUE, true, false, false, null);
        channel.queueDeclare(WORKFLOW_RESPONSE_QUEUE, true, false, false, null);
        
        // Set prefetch for concurrent processing
        channel.basicQos(10);
        
        System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST);
        
        // Execute startup workflows BEFORE listening for alerts
        executeStartupWorkflows();
        
        System.out.println("⏳ Waiting for alerts from queue: " + WORKFLOW_QUEUE);
        System.out.println("   Press CTRL+C to exit.\n");
        
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            messageCounter++;
            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            boolean redelivered = delivery.getEnvelope().isRedeliver();
            
            System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("📨 Alert #" + messageCounter + " received (deliveryTag=" + deliveryTag + ", redelivered=" + redelivered + ")");
            
            try {
                String message = new String(delivery.getBody(), "UTF-8");
                JSONObject alert = new JSONObject(message);
                
                String eventId = alert.optString("event_id", "unknown");
                String messageType = alert.optString("message_type", "unknown");
                
                System.out.println("🏷️  Event ID: " + eventId);
                System.out.println("🔖 Message Type: " + messageType);
                
                // Check if this is an alert message
                if (!messageType.equals("alert")) {
                    System.out.println("⚠️  Not an alert message, skipping workflow processing");
                    channel.basicAck(deliveryTag, false);
                    System.out.println("✅ ACK sent");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                    return;
                }
                
                // Check if alert is ransomware-related
                if (!isRansomwareAlert(alert)) {
                    System.out.println("⚠️  Not a ransomware alert, skipping workflow processing");
                    System.out.println("   Values checked: " + alert.toString());
                    channel.basicAck(deliveryTag, false);
                    System.out.println("✅ ACK sent");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                    return;
                }

                normalizeAlertForRansomwareWorkflows(alert);
                
                // Extract alert details
                if (alert.has("payload")) {
                    JSONObject payload = alert.getJSONObject("payload");
                    System.out.println("📊 Alert Type: " + payload.optString("alert_type", payload.optString("alertType", "unknown")));
                    System.out.println("🎯 Severity: " + payload.optString("severity", "unknown"));
                    System.out.println("⚡ Threat Score: " + payload.optInt("threat_score", payload.optInt("threatScore", 0)));
                }
                
                // Load workflows
                System.out.println("\n📂 Loading workflows...");
                String workflowsDir = ConfigLoader.getWorkflowsDirectory();
                List<Workflow> allWorkflows = workflowLoader.loadWorkflows(workflowsDir);
                
                if (allWorkflows.isEmpty()) {
                    System.err.println("⚠️  No workflows found!");
                    channel.basicAck(deliveryTag, false);
                    System.out.println("✅ ACK sent");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                    return;
                }
                
                // Find and execute general workflow first
                System.out.println("\n🔍 Looking for general ransomware workflow...");
                Workflow generalWorkflow = workflowLoader.findGeneralWorkflow(allWorkflows);
                
                if (generalWorkflow != null) {
                    System.out.println("✅ Found general workflow: " + generalWorkflow.getName());
                    workflowExecutor.executeWorkflow(generalWorkflow, alert, channel);
                } else {
                    System.err.println("⚠️  No general workflow found!");
                }
                
                // Find and execute specific matching workflows
                System.out.println("\n🔍 Matching against specific workflows...");
                List<Workflow> matchingWorkflows = workflowMatcher.findMatchingWorkflows(alert, allWorkflows);
                
                // Remove general workflow from matches (already executed)
                if (generalWorkflow != null) {
                    matchingWorkflows.remove(generalWorkflow);
                }
                
                if (matchingWorkflows.isEmpty()) {
                    System.out.println("📭 No specific workflows matched");
                } else {
                    System.out.println("✅ Found " + matchingWorkflows.size() + " matching specific workflow(s)");
                    
                    for (Workflow workflow : matchingWorkflows) {
                        workflowExecutor.executeWorkflow(workflow, alert, channel);
                    }
                }
                
                // Acknowledge message
                long processingEndTime = System.currentTimeMillis();
                long processingTimeStr = processingEndTime - System.currentTimeMillis(); // Just for delta, but we want Total Turnaround Time

                // Calculate Total Containment Time if timestamp is available
                if (alert.has("timestamp")) {
                    try {
                        String alertTimeStr = alert.getString("timestamp");
                        java.time.Instant alertTime = java.time.Instant.parse(alertTimeStr);
                        long alertTimeMillis = alertTime.toEpochMilli();
                        long totalContainmentTime = processingEndTime - alertTimeMillis;
                        
                        System.out.println("⏱️  Containment Performance Metrics:");
                        System.out.println("   - Alert Generation: " + alertTimeStr);
                        System.out.println("   - Action Executed:  " + java.time.Instant.now().toString());
                        System.out.println("   - TOTAL TIME:       " + totalContainmentTime + " ms");
                    } catch (Exception e) {
                        System.out.println("⚠️  Could not calculate total time: " + e.getMessage());
                    }
                }

                channel.basicAck(deliveryTag, false);
                System.out.println("\n✅ ACK sent for alert #" + messageCounter);
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                
            } catch (Exception e) {
                System.err.println("\n❌❌❌ EXCEPTION processing alert #" + messageCounter + " ❌❌❌");
                System.err.println("Error message: " + e.getMessage());
                System.err.println("Exception type: " + e.getClass().getName());
                System.err.println("\nFull stack trace:");
                e.printStackTrace();
                
                if (redelivered) {
                    System.err.println("\n⚠️  This message ALREADY FAILED ONCE (redelivered=true)");
                    System.err.println("⚠️  DISCARDING alert #" + messageCounter + " to prevent infinite loop");
                    channel.basicNack(deliveryTag, false, false);
                    System.err.println("🗑️  NACK sent (requeue=false)");
                } else {
                    System.err.println("\n⚠️  This is the FIRST FAILURE (redelivered=false)");
                    System.err.println("⚠️  REQUEUING alert #" + messageCounter + " for one retry");
                    channel.basicNack(deliveryTag, false, true);
                    System.err.println("🔄 NACK sent (requeue=true)");
                }
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            }
        };
        
        // Start consuming
        channel.basicConsume(WORKFLOW_QUEUE, false, deliverCallback, consumerTag -> {
            System.out.println("Consumer cancelled: " + consumerTag);
        });
        
        // Keep listener running
        keepAlive();
    }
    
    /**
     * Check if alert is ransomware-related
     */
    private boolean isRansomwareAlert(JSONObject alert) {
        if (!alert.has("payload")) {
            return false;
        }
        
        JSONObject payload = alert.getJSONObject("payload");
        
        String alertType = payload.optString("alert_type", payload.optString("alertType", ""));
        String category = payload.optString("category", "");
        String noteType = payload.optString("note_type", payload.optString("noteType", ""));
        String signature = payload.optString("signature", payload.optString("ruleDescription", ""));

        return alertType.toLowerCase().contains("ransomware") ||
               category.toLowerCase().contains("ransomware") ||
               noteType.toLowerCase().contains("ransomware") ||
               noteType.toLowerCase().contains("smb_mapping_event") ||
               isLikelyRansomwareByContent(alertType, category, noteType, signature) ||
               isHighRiskSuricataAlert(alert);
    }

    private void normalizeAlertForRansomwareWorkflows(JSONObject alert) {
        if (!alert.has("payload")) {
            return;
        }

        JSONObject payload = alert.getJSONObject("payload");
        String currentAlertType = payload.optString("alert_type", payload.optString("alertType", ""));
        String currentAlertTypeLower = currentAlertType.toLowerCase();

        if (currentAlertTypeLower.contains("ransomware")) {
            return;
        }

        String category = payload.optString("category", "");
        String noteType = payload.optString("note_type", payload.optString("noteType", ""));
        String signature = payload.optString("signature", payload.optString("ruleDescription", ""));

        if (isLikelyRansomwareByContent(currentAlertType, category, noteType, signature) || isHighRiskSuricataAlert(alert)) {
            if (!currentAlertType.isBlank()) {
                payload.put("original_alert_type", currentAlertType);
            }
            payload.put("alert_type", "ransomware_detection");
            System.out.println("ℹ️  Normalized high-risk alert to alert_type=ransomware_detection for workflow matching");
        }
    }

    private boolean isLikelyRansomwareByContent(String alertType, String category, String noteType, String signature) {
        String combined = String.join(" ",
                safeLower(alertType),
                safeLower(category),
                safeLower(noteType),
                safeLower(signature));

        return combined.contains("ransomware")
                || combined.contains("eternalblue")
                || combined.contains("ms17-010")
                || combined.contains("wannacry")
                || combined.contains("smb")
                || combined.contains("lateral")
                || combined.contains("trojan");
    }

    private boolean isHighRiskSuricataAlert(JSONObject alert) {
        if (!alert.has("payload")) {
            return false;
        }

        JSONObject payload = alert.getJSONObject("payload");
        String eventType = safeLower(alert.optString("event_type", ""));
        String sourceModule = safeLower(alert.optString("source_module", ""));
        boolean isSuricataEvent = eventType.contains("suricata") || sourceModule.contains("suricata");

        if (!isSuricataEvent) {
            return false;
        }

        String severity = safeLower(payload.optString("severity", ""));
        int threatScore = payload.optInt("threat_score", payload.optInt("threatScore", 0));
        int confidenceScore = payload.optInt("confidence_score", payload.optInt("confidenceScore", 0));

        boolean highSeverity = severity.equals("critical") || severity.equals("high");
        boolean highScore = threatScore >= 80 || confidenceScore >= 80;

        return highSeverity && highScore;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }
    
    /**
     * Keep the application running
     */
    private void keepAlive() {
        try {
            synchronized (this) {
                this.wait();
            }
        } catch (InterruptedException e) {
            System.out.println("Listener interrupted, shutting down...");
            shutdown();
        }
    }
    
    /**
     * Execute startup workflows (proactive policies)
     * These run once when the system starts, before processing alerts
     */
    private void executeStartupWorkflows() {
        try {
            System.out.println("\n🚀 Executing startup workflows...");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // Load all workflows
            String workflowsDir = ConfigLoader.getWorkflowsDirectory();
            List<Workflow> allWorkflows = workflowLoader.loadAndCacheWorkflows(workflowsDir);
            
            // Filter for startup workflows
            List<Workflow> startupWorkflows = workflowLoader.getWorkflowsByTriggerType("system.startup");
            
            if (startupWorkflows.isEmpty()) {
                System.out.println("📭 No startup workflows found (trigger: system.startup)");
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                return;
            }
            
            System.out.println("✅ Found " + startupWorkflows.size() + " startup workflow(s)");
            
            // Create synthetic startup event
            JSONObject startupEvent = new JSONObject();
            startupEvent.put("event_type", "system.startup");
            startupEvent.put("event_id", java.util.UUID.randomUUID().toString());
            startupEvent.put("timestamp", java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Manila"))
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            startupEvent.put("message_type", "system_event");
            startupEvent.put("source_module", "WorkflowEngine");
            
            JSONObject payload = new JSONObject();
            payload.put("event_name", "system_startup");
            payload.put("description", "System startup - executing proactive policies");
            startupEvent.put("payload", payload);
            
            // Execute each startup workflow
            for (Workflow workflow : startupWorkflows) {
                System.out.println("\n   Running: " + workflow.getName());
                workflowExecutor.executeWorkflow(workflow, startupEvent, channel);
            }
            
            System.out.println("\n✅ Startup workflows completed");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            
        } catch (Exception e) {
            System.err.println("❌ Error executing startup workflows: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Gracefully shutdown the listener
     */
    public void shutdown() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
            System.out.println("✅ RabbitMQ connection closed gracefully.");
        } catch (Exception e) {
            System.err.println("Error during shutdown:");
            e.printStackTrace();
        }
    }
}

