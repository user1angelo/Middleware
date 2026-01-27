package com.nis1.thesis.udm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * SimpleMitigationTester - Quick test tool to send INITIATE_MITIGATION events
 * 
 * Edit the variables in main() and run to send test mitigation commands.
 */
public class SimpleMitigationTester {

    // ==================== EDIT THESE VARIABLES ====================
    
    // RabbitMQ connection
    private static final String RABBITMQ_HOST = "localhost";
    private static final int RABBITMQ_PORT = 5672;
    private static final String RABBITMQ_USER = "user";
    private static final String RABBITMQ_PASSWORD = "password";
    private static final String WORKFLOW_QUEUE = "workflow_queue";
    
    // Mitigation command details
    private static final String TARGET_IP = "192.168.1.100";        // ← CHANGE THIS
    private static final String MITIGATION_ACTION = "BLOCK_IP";     // BLOCK_IP, QUARANTINE, ISOLATE_VLAN, etc.
    private static final String JUSTIFICATION = "Testing OpenDaylightModule with simple tester";
    
    // Optional metadata
    private static final String SOURCE_MODULE = "SimpleMitigationTester";
    private static final String WORKFLOW_NAME = "manual_test_workflow";
    
    // ==============================================================

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║      Simple Mitigation Tester for OpenDaylightModule      ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  RabbitMQ: " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);
        System.out.println("  Queue: " + WORKFLOW_QUEUE);
        System.out.println("  Target IP: " + TARGET_IP);
        System.out.println("  Action: " + MITIGATION_ACTION);
        System.out.println("  Justification: " + JUSTIFICATION);
        System.out.println();

        try {
            sendMitigationCommand();
            System.out.println("✅ Test command sent successfully!");
            System.out.println();
            System.out.println("Check OpenDaylightModule logs for:");
            System.out.println("  - Handling mitigation command " + MITIGATION_ACTION);
            System.out.println("  - Target: " + TARGET_IP);
            System.out.println();
        } catch (Exception e) {
            System.err.println("❌ Error sending command: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void sendMitigationCommand() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);

        // Generate unique IDs
        String eventId = UUID.randomUUID().toString();
        String commandId = UUID.randomUUID().toString();
        String workflowInstanceId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();

        // Build SDK Event envelope
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("event_type", "INITIATE_MITIGATION");
        envelope.put("event_id", eventId);
        envelope.put("timestamp", timestamp);
        envelope.put("source_module", SOURCE_MODULE);

        // Build MitigationCommandData payload
        ObjectNode payload = mapper.createObjectNode();
        payload.put("commandId", commandId);
        payload.put("workflowInstanceId", workflowInstanceId);
        payload.put("workflowName", WORKFLOW_NAME);
        payload.put("action", MITIGATION_ACTION);
        payload.put("targetHost", TARGET_IP);
        payload.put("justification", JUSTIFICATION);
        payload.put("timestamp", timestamp);

        envelope.set("data", payload);

        String message = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);

        System.out.println("📤 Publishing event to queue '" + WORKFLOW_QUEUE + "':");
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.println(message);
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.println();

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            channel.queueDeclare(WORKFLOW_QUEUE, true, false, false, null);
            channel.basicPublish("", WORKFLOW_QUEUE, null, message.getBytes(StandardCharsets.UTF_8));

            System.out.println("✅ Published to RabbitMQ");
            System.out.println("   Event ID: " + eventId);
            System.out.println("   Command ID: " + commandId);
            System.out.println("   Workflow Instance: " + workflowInstanceId);
        }
    }
}