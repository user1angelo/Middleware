import com.rabbitmq.client.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * WorkflowTester - End-to-end test for Workflow Engine
 * 
 * Flow:
 * 1. Sends alert to workflow_queue (ModuleRegistry listens)
 * 2. ModuleRegistry broadcasts to alerts_queue + workflow_queue
 * 3. WorkflowEngine processes and sends commands to workflow_response_queue
 * 4. This script monitors and displays responses
 */
public class WorkflowTester {
    
    // RabbitMQ Configuration (from WorkflowEngine config.properties)
    private static final String RABBITMQ_HOST = "192.168.1.8";
    private static final int RABBITMQ_PORT = 5672;
    private static final String RABBITMQ_USER = "user";
    private static final String RABBITMQ_PASSWORD = "password";
    
    // Queue Names
    private static final String WORKFLOW_QUEUE = "workflow_queue";
    private static final String WORKFLOW_RESPONSE_QUEUE = "workflow_response_queue";
    
    // Test Data
    private static int alertCounter = 0;
    
    public static void main(String[] args) {
        printHeader();
        
        Scanner scanner = new Scanner(System.in);
        boolean running = true;
        
        while (running) {
            printMenu();
            System.out.print("Choice: ");
            String choice = scanner.nextLine();
            
            switch (choice) {
                case "1":
                    sendSingleAlert();
                    break;
                case "2":
                    sendMultipleAlerts(10);
                    break;
                case "3":
                    monitorWorkflowResponses();
                    break;
                case "4":
                    sendDharmaFileEncryptionAlert();
                    break;
                case "5":
                    running = false;
                    System.out.println("\n👋 Goodbye!\n");
                    break;
                default:
                    System.out.println("❌ Invalid choice");
            }
        }
        
        scanner.close();
    }
    
    private static void printHeader() {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║   WorkflowEngine End-to-End Tester               ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");
        System.out.println("RabbitMQ: " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);
        System.out.println("Target Queue: " + WORKFLOW_QUEUE);
        System.out.println("Response Queue: " + WORKFLOW_RESPONSE_QUEUE);
        System.out.println();
    }
    
    private static void printMenu() {
        System.out.println("\n═══ Workflow Tester Menu ═══");
        System.out.println("1. Send single ransomware alert");
        System.out.println("2. Send 10 ransomware alerts");
        System.out.println("3. Monitor workflow_response_queue");
        System.out.println("4. Send Dharma file encryption alert (specific workflow)");
        System.out.println("5. Exit");
        System.out.println();
    }
    
    /**
     * Send a single generic ransomware alert
     */
    private static void sendSingleAlert() {
        try {
            JSONObject alert = createRansomwareAlert();
            sendToQueue(WORKFLOW_QUEUE, alert);
            alertCounter++;
            
            System.out.println("\n✅ Sent alert #" + alertCounter);
            System.out.println("   Event ID: " + alert.getString("event_id"));
            System.out.println("   Alert Type: " + alert.getJSONObject("payload").getString("alert_type"));
            System.out.println("   Severity: " + alert.getJSONObject("payload").getString("severity"));
            
        } catch (Exception e) {
            System.err.println("❌ Failed to send alert: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Send multiple ransomware alerts
     */
    private static void sendMultipleAlerts(int count) {
        System.out.println("\n📤 Sending " + count + " ransomware alerts...");
        
        for (int i = 0; i < count; i++) {
            try {
                JSONObject alert = createRansomwareAlert();
                sendToQueue(WORKFLOW_QUEUE, alert);
                alertCounter++;
                
                System.out.println("   ✅ Alert #" + alertCounter + " sent (Event ID: " + 
                                 alert.getString("event_id").substring(0, 8) + "...)");
                
                Thread.sleep(100); // Small delay between messages
                
            } catch (Exception e) {
                System.err.println("   ❌ Failed to send alert #" + (i + 1) + ": " + e.getMessage());
            }
        }
        
        System.out.println("\n✅ Sent " + count + " alerts to " + WORKFLOW_QUEUE);
    }
    
    /**
     * Send Dharma file encryption alert (triggers specific workflow)
     */
    private static void sendDharmaFileEncryptionAlert() {
        try {
            JSONObject alert = createDharmaFileEncryptionAlert();
            sendToQueue(WORKFLOW_QUEUE, alert);
            alertCounter++;
            
            System.out.println("\n✅ Sent Dharma file encryption alert");
            System.out.println("   Event ID: " + alert.getString("event_id"));
            System.out.println("   Alert Type: " + alert.getJSONObject("payload").getString("alert_type"));
            System.out.println("   Rule Description: " + alert.getJSONObject("payload").getString("ruleDescription"));
            System.out.println("\n   Should trigger: 'Dharma Ransomware File Encryption Detection' workflow");
            
        } catch (Exception e) {
            System.err.println("❌ Failed to send alert: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Monitor workflow_response_queue and display messages
     */
    private static void monitorWorkflowResponses() {
        System.out.println("\n📡 Monitoring workflow_response_queue...");
        System.out.println("   Press ENTER to stop monitoring\n");
        
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);
        
        Thread monitorThread = new Thread(() -> {
            try (Connection connection = factory.newConnection();
                 Channel channel = connection.createChannel()) {
                
                channel.queueDeclare(WORKFLOW_RESPONSE_QUEUE, true, false, false, null);
                
                System.out.println("✅ Connected to " + WORKFLOW_RESPONSE_QUEUE);
                System.out.println("   Waiting for workflow responses...\n");
                
                int responseCount = 0;
                
                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    try {
                        String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                        JSONObject response = new JSONObject(message);
                        
                        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        System.out.println("📨 WORKFLOW RESPONSE #" + (responseCount + 1));
                        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        
                        // Display message details
                        System.out.println("Message Type: " + response.optString("message_type"));
                        System.out.println("Event ID: " + response.optString("event_id"));
                        System.out.println("Source: " + response.optString("source_module"));
                        
                        if (response.has("payload")) {
                            JSONObject payload = response.getJSONObject("payload");
                            System.out.println("\n📦 Payload:");
                            
                            if (payload.has("workflow_execution_id")) {
                                System.out.println("   Workflow Execution ID: " + payload.getString("workflow_execution_id"));
                            }
                            if (payload.has("workflow_name")) {
                                System.out.println("   Workflow Name: " + payload.getString("workflow_name"));
                            }
                            if (payload.has("command")) {
                                System.out.println("   Command: " + payload.getString("command"));
                            }
                            if (payload.has("action")) {
                                System.out.println("   Action: " + payload.getString("action"));
                            }
                            if (payload.has("target_module")) {
                                System.out.println("   Target Module: " + payload.getString("target_module"));
                            }
                            if (payload.has("parameters")) {
                                System.out.println("   Parameters: " + payload.getJSONObject("parameters").toString(2));
                            }
                        }
                        
                        System.out.println("\n📄 Full Message:");
                        System.out.println(response.toString(2));
                        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                        
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        
                    } catch (Exception e) {
                        System.err.println("❌ Error processing response: " + e.getMessage());
                        e.printStackTrace();
                    }
                };
                
                channel.basicConsume(WORKFLOW_RESPONSE_QUEUE, false, deliverCallback, consumerTag -> {});
                
                // Keep monitoring until interrupted
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1000);
                }
                
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    System.err.println("❌ Monitor error: " + e.getMessage());
                }
            }
        });
        
        monitorThread.start();
        
        // Wait for user to press ENTER
        Scanner scanner = new Scanner(System.in);
        scanner.nextLine();
        
        // Stop monitoring
        monitorThread.interrupt();
        try {
            monitorThread.join(2000);
        } catch (InterruptedException e) {
            // Ignore
        }
        
        System.out.println("\n✅ Stopped monitoring");
    }
    
    /**
     * Create a generic ransomware alert
     */
    private static JSONObject createRansomwareAlert() {
        JSONObject alert = new JSONObject();
        
        String eventId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        
        alert.put("message_type", "alert");
        alert.put("event_id", eventId);
        alert.put("timestamp", timestamp);
        alert.put("event_type", "alerts.host.wazuh");
        alert.put("source_module", "WorkflowTester");
        
        JSONObject payload = new JSONObject();
        payload.put("severity", "high");
        payload.put("alert_type", "ransomware_detection");
        payload.put("host_id", "host-192.168.1." + (100 + (int)(Math.random() * 50)));
        payload.put("threat_score", 85 + (int)(Math.random() * 15));
        payload.put("signature", "Ransomware behavior detected");
        payload.put("source_ip", "192.168.1." + (100 + (int)(Math.random() * 50)));
        payload.put("destination_ip", "10.0.0." + (1 + (int)(Math.random() * 254)));
        payload.put("protocol", "TCP");
        payload.put("port", 445);
        
        alert.put("payload", payload);
        
        return alert;
    }
    
    /**
     * Create Dharma file encryption alert (specific workflow trigger)
     */
    private static JSONObject createDharmaFileEncryptionAlert() {
        JSONObject alert = new JSONObject();
        
        String eventId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        
        alert.put("message_type", "alert");
        alert.put("event_id", eventId);
        alert.put("timestamp", timestamp);
        alert.put("event_type", "HOST_ALERT_WAZUH");
        alert.put("source_module", "WorkflowTester");
        
        JSONObject payload = new JSONObject();
        payload.put("severity", "critical");
        payload.put("alert_type", "ransomware_file_encryption");
        payload.put("host_id", "host-192.168.1.105");
        payload.put("threat_score", 95);
        payload.put("ruleDescription", "Dharma Ransomware File Encryption Detected");
        payload.put("sourceIp", "192.168.1.105");
        payload.put("signature", "Rapid file modification with .dharma extension");
        payload.put("affected_files", 247);
        payload.put("file_extensions", new JSONArray().put(".dharma").put(".wallet").put(".onion"));
        
        alert.put("payload", payload);
        
        return alert;
    }
    
    /**
     * Send message to RabbitMQ queue
     */
    private static void sendToQueue(String queueName, JSONObject message) 
            throws IOException, TimeoutException {
        
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);
        
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            
            channel.queueDeclare(queueName, true, false, false, null);
            
            byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);
            channel.basicPublish("", queueName, null, messageBytes);
        }
    }
}

