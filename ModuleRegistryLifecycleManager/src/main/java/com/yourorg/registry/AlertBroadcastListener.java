package com.yourorg.registry;

import com.rabbitmq.client.*;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AlertBroadcastListener - Receives alerts from UDMs and broadcasts them
 * 
 * Flow:
 * 1. Listens to workflow_queue for UDM alerts
 * 2. Processes registration, heartbeat, and alert messages
 * 3. Broadcasts alerts to both alerts_queue AND workflow_queue
 * 4. Routes ODL/Workflow commands to SDK modules
 * 
 * Message Types Handled:
 * - registration: Register new UDM
 * - heartbeat: Update UDM health status
 * - connection_status: Handle UDM connection changes
 * - alert: Broadcast security alerts to both queues
 */
public class AlertBroadcastListener implements Runnable {

    private final ModuleRegistry registry;
    private final SdkModuleHost sdkModuleHost;
    private volatile boolean running = true;

    public AlertBroadcastListener(ModuleRegistry registry, SdkModuleHost sdkModuleHost) {
        this.registry = registry;
        this.sdkModuleHost = sdkModuleHost;
    }

    @Override
    public void run() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(ConfigLoader.getRabbitMQHost());
        factory.setPort(ConfigLoader.getRabbitMQPort());
        factory.setUsername(ConfigLoader.getRabbitMQUser());
        factory.setPassword(ConfigLoader.getRabbitMQPassword());

        try (Connection connection = factory.newConnection();
                Channel channel = connection.createChannel()) {

            String workflowQueue = ConfigLoader.getWorkflowQueueName();
            String alertsQueue = ConfigLoader.getAlertsQueueName();

            // Declare queues
            channel.queueDeclare(workflowQueue, true, false, false, null);
            channel.queueDeclare(alertsQueue, true, false, false, null);

            System.out.println("📡 AlertBroadcastListener started");
            System.out.println("   Listening on: " + workflowQueue);
            System.out.println("   Broadcasting to: " + alertsQueue + " + " + workflowQueue);

            // Set up consumer
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                try {
                    String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    processMessage(message, channel);

                    // Acknowledge message
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                } catch (Exception e) {
                    System.err.println("❌ Error processing message: " + e.getMessage());
                    e.printStackTrace();

                    // Reject and requeue on error
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                }
            };

            channel.basicConsume(workflowQueue, false, deliverCallback, consumerTag -> {
            });

            // Keep running
            while (running) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println("❌ AlertBroadcastListener error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Process incoming message based on message_type
     */
    private void processMessage(String message, Channel channel) throws IOException {
        JSONObject json = new JSONObject(message);
        String messageType = json.optString("message_type", "unknown");
        String sourceModule = json.optString("source_module", "unknown");

        System.out.println("📨 Received message type: " + messageType + " from " + sourceModule);

        switch (messageType) {
            case "registration":
                handleRegistration(json);
                break;

            case "heartbeat":
                handleHeartbeat(json);
                break;

            case "connection_status":
                handleConnectionStatus(json);
                break;

            case "alert":
                handleAlert(json, channel);
                break;

            default:
                if (messageType.startsWith("odl.") || "workflow.command".equals(messageType)) {
                    System.out.println("➡️  Routing " + messageType + " to SDK modules");
                    if (sdkModuleHost != null) {
                        sdkModuleHost.dispatch(json);
                    }
                } else {
                    System.out.println("⚠️  Unknown message type: " + messageType);
                }
        }
    }

    /**
     * Handle module registration
     */
    private void handleRegistration(JSONObject registration) {
        System.out.println("📝 Processing registration...");
        registry.registerModule(registration);
    }

    /**
     * Handle heartbeat update
     */
    private void handleHeartbeat(JSONObject heartbeat) {
        try {
            JSONObject payload = heartbeat.getJSONObject("payload");
            String moduleId = payload.getString("module_id");

            System.out.println("💓 Heartbeat from: " + moduleId);
            registry.updateHeartbeat(moduleId);

        } catch (Exception e) {
            System.err.println("❌ Failed to process heartbeat: " + e.getMessage());
        }
    }

    /**
     * Handle connection status update
     */
    private void handleConnectionStatus(JSONObject status) {
        try {
            JSONObject payload = status.getJSONObject("payload");
            String moduleId = payload.getString("module_id");
            String connectionStatus = payload.getString("status");

            System.out.println("🔌 Connection status from " + moduleId + ": " + connectionStatus);

            if ("offline".equals(connectionStatus)) {
                registry.markModuleOffline(moduleId);
            } else if ("online".equals(connectionStatus)) {
                registry.updateHeartbeat(moduleId);
            }

        } catch (Exception e) {
            System.err.println("❌ Failed to process connection status: " + e.getMessage());
        }
    }

    /**
     * Handle security alert - broadcast to both queues
     */
    private void handleAlert(JSONObject alert, Channel channel) throws IOException {
        try {
            String alertsQueue = ConfigLoader.getAlertsQueueName();
            String workflowQueue = ConfigLoader.getWorkflowQueueName();

            byte[] messageBytes = alert.toString().getBytes(StandardCharsets.UTF_8);

            // Broadcast to alerts_queue (for ThreatContextStore)
            channel.basicPublish("", alertsQueue, null, messageBytes);

            // Broadcast to workflow_queue (for WorkflowEngine)
            channel.basicPublish("", workflowQueue, null, messageBytes);

            String severity = alert.optJSONObject("payload") != null
                    ? alert.getJSONObject("payload").optString("severity", "unknown")
                    : "unknown";
            String alertType = alert.optJSONObject("payload") != null
                    ? alert.getJSONObject("payload").optString("alert_type", "unknown")
                    : "unknown";

            System.out.println("📤 Broadcasted alert | Severity: " + severity +
                    " | Type: " + alertType + " | To: both queues");

        } catch (Exception e) {
            System.err.println("❌ Failed to broadcast alert: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Stop the listener
     */
    public void stop() {
        running = false;
    }
}
