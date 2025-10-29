import com.rabbitmq.client.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UDMTester - Sample User-Defined Module (Wazuh Simulator)
 * 
 * Simulates a Wazuh security module that:
 * 1. Registers with ModuleRegistry
 * 2. Sends periodic heartbeats
 * 3. Generates ransomware security alerts
 * 4. Listens for commands on its command queue
 * 
 * Usage: java -cp ".:lib/*" UDMTester
 */
public class UDMTester {
    
    private static final String MODULE_ID = "wazuh_udm_01";
    private static final String MODULE_NAME = "Wazuh Security Module (UDM)";
    private static final String MODULE_TYPE = "security_monitoring";
    private static final String COMMAND_QUEUE = "wazuh_commands_queue";
    
    private static final String RABBITMQ_HOST = "192.168.86.76";
    private static final int RABBITMQ_PORT = 5672;
    private static final String RABBITMQ_USER = "guest";
    private static final String RABBITMQ_PASSWORD = "guest";
    private static final String WORKFLOW_QUEUE = "workflow_queue";
    
    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Random random = new Random();
    private static final Scanner scanner = new Scanner(System.in);
    
    private static final String[] SEVERITIES = {"high", "critical"};
    private static final String[] ALERT_TYPES = {
        "ransomware_detection", "ransomware_encryption", "ransomware_propagation"
    };
    private static final String[] SIGNATURES = {
        "Suspicious file encryption activity", "Mass file modification detected",
        "Ransomware encryption pattern", "Crypto-locker behavior detected",
        "Volume shadow copy deletion", "Shell command execution"
    };
    
    private static ScheduledExecutorService scheduler;
    private static volatile boolean running = true;
    
    public static void main(String[] args) {
        printBanner();
        
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(RABBITMQ_HOST);
            factory.setPort(RABBITMQ_PORT);
            factory.setUsername(RABBITMQ_USER);
            factory.setPassword(RABBITMQ_PASSWORD);
            
            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();
            
            // Declare queues
            channel.queueDeclare(WORKFLOW_QUEUE, true, false, false, null);
            channel.queueDeclare(COMMAND_QUEUE, true, false, false, null);
            
            System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST);
            
            // Step 1: Register module
            sendRegistration(channel);
            Thread.sleep(1000);
            
            // Step 2: Start heartbeat sender
            startHeartbeats(channel);
            
            // Step 3: Start command listener
            startCommandListener(channel);
            
            // Step 4: Interactive menu
            runInteractiveMenu(channel);
            
            // Cleanup
            scheduler.shutdown();
            channel.close();
            connection.close();
            
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printBanner() {
        System.out.println("╔═══════════════════════════════════════╗");
        System.out.println("║  UDM Tester - Wazuh Module Simulator ║");
        System.out.println("╚═══════════════════════════════════════╝\n");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Module Type: " + MODULE_TYPE);
        System.out.println("Command Queue: " + COMMAND_QUEUE + "\n");
    }
    
    private static void sendRegistration(Channel channel) throws Exception {
        JSONObject registration = new JSONObject();
        registration.put("message_type", "registration");
        registration.put("event_id", "reg-" + UUID.randomUUID().toString());
        registration.put("timestamp", getCurrentManilaTime());
        registration.put("event_type", "system.module.registration");
        registration.put("source_module", MODULE_NAME);
        
        JSONObject payload = new JSONObject();
        payload.put("module_id", MODULE_ID);
        payload.put("module_name", MODULE_NAME);
        payload.put("module_type", MODULE_TYPE);
        
        JSONArray capabilities = new JSONArray();
        capabilities.put("wazuh_monitoring");
        capabilities.put("alert_generation");
        capabilities.put("log_analysis");
        payload.put("capabilities", capabilities);
        
        payload.put("command_queue", COMMAND_QUEUE);
        payload.put("version", "1.0.0");
        
        JSONObject metadata = new JSONObject();
        metadata.put("vendor", "Wazuh");
        metadata.put("api_version", "4.0");
        payload.put("metadata", metadata);
        
        registration.put("payload", payload);
        
        channel.basicPublish("", WORKFLOW_QUEUE, null, registration.toString().getBytes("UTF-8"));
        System.out.println("📝 Sent registration to ModuleRegistry");
    }
    
    private static void startHeartbeats(Channel channel) {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeat(channel);
            } catch (Exception e) {
                System.err.println("❌ Heartbeat failed: " + e.getMessage());
            }
        }, 5, 30, TimeUnit.SECONDS);
        
        System.out.println("💓 Heartbeat sender started (every 30 seconds)\n");
    }
    
    private static void sendHeartbeat(Channel channel) throws Exception {
        JSONObject heartbeat = new JSONObject();
        heartbeat.put("message_type", "heartbeat");
        heartbeat.put("event_id", "hb-" + UUID.randomUUID().toString());
        heartbeat.put("timestamp", getCurrentManilaTime());
        heartbeat.put("event_type", "system.module.heartbeat");
        heartbeat.put("source_module", MODULE_NAME);
        
        JSONObject payload = new JSONObject();
        payload.put("module_id", MODULE_ID);
        payload.put("status", "online");
        payload.put("uptime_seconds", random.nextInt(86400));
        
        heartbeat.put("payload", payload);
        
        channel.basicPublish("", WORKFLOW_QUEUE, null, heartbeat.toString().getBytes("UTF-8"));
        System.out.println("💓 Sent heartbeat");
    }
    
    private static void startCommandListener(Channel channel) {
        new Thread(() -> {
            try {
                System.out.println("🎯 Command listener started on: " + COMMAND_QUEUE + "\n");
                
                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    String message = new String(delivery.getBody(), "UTF-8");
                    JSONObject json = new JSONObject(message);
                    
                    System.out.println("\n🎯 Received command:");
                    System.out.println(json.toString(2));
                    
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                };
                
                channel.basicConsume(COMMAND_QUEUE, false, deliverCallback, consumerTag -> {});
                
            } catch (Exception e) {
                System.err.println("❌ Command listener error: " + e.getMessage());
            }
        }).start();
    }
    
    private static void runInteractiveMenu(Channel channel) throws Exception {
        while (running) {
            System.out.println("\n═══ UDM Tester Menu ═══");
            System.out.println("1. Send single ransomware alert");
            System.out.println("2. Send 10 ransomware alerts");
            System.out.println("3. Send heartbeat now");
            System.out.println("4. Exit");
            System.out.print("\nChoice: ");
            
            String choice = scanner.nextLine().trim();
            
            switch (choice) {
                case "1":
                    sendAlert(channel);
                    break;
                case "2":
                    for (int i = 1; i <= 10; i++) {
                        sendAlert(channel);
                        System.out.println("   Sent alert " + i + "/10");
                        Thread.sleep(500);
                    }
                    System.out.println("✅ Sent 10 alerts");
                    break;
                case "3":
                    sendHeartbeat(channel);
                    break;
                case "4":
                    running = false;
                    System.out.println("\n👋 Shutting down UDMTester...");
                    break;
                default:
                    System.out.println("❌ Invalid choice");
            }
        }
    }
    
    private static void sendAlert(Channel channel) throws Exception {
        JSONObject alert = new JSONObject();
        alert.put("message_type", "alert");
        alert.put("event_id", UUID.randomUUID().toString());
        alert.put("timestamp", getCurrentManilaTime());
        alert.put("event_type", "alerts.host.wazuh");
        alert.put("source_module", MODULE_NAME);
        
        JSONObject payload = new JSONObject();
        payload.put("severity", SEVERITIES[random.nextInt(SEVERITIES.length)]);
        payload.put("alert_type", ALERT_TYPES[random.nextInt(ALERT_TYPES.length)]);
        payload.put("signature_id", String.valueOf(9200000 + random.nextInt(10000)));
        payload.put("signature", SIGNATURES[random.nextInt(SIGNATURES.length)]);
        payload.put("host_id", "host-192.168." + random.nextInt(256) + "." + random.nextInt(256));
        payload.put("source_ip", "192.168." + random.nextInt(256) + "." + random.nextInt(256));
        payload.put("destination_ip", "10.0." + random.nextInt(256) + "." + random.nextInt(256));
        payload.put("protocol", "TCP");
        payload.put("process", "/tmp/suspicious_process" + random.nextInt(1000));
        payload.put("file_path", "/home/user/encrypted_file" + random.nextInt(1000) + ".dat");
        payload.put("threat_score", 70 + random.nextInt(31));
        payload.put("matched_rule", "ransomware_rule_" + random.nextInt(100));
        
        alert.put("payload", payload);
        
        channel.basicPublish("", WORKFLOW_QUEUE, null, alert.toString().getBytes("UTF-8"));
        System.out.println("📤 Sent ransomware alert | Severity: " + payload.getString("severity") + 
                         " | Type: " + payload.getString("alert_type"));
    }
    
    private static String getCurrentManilaTime() {
        ZonedDateTime manilaTime = ZonedDateTime.now(MANILA_ZONE);
        return manilaTime.format(ISO_FORMATTER);
    }
}

