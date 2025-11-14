import com.rabbitmq.client.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.lang.management.ManagementFactory;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Suricata Network Intrusion Detection System (NIDS) integration module
 * 
 * Monitors Suricata's eve.json log file in real-time and publishes alerts
 * to the SOAR framework via RabbitMQ.
 * 
 * Features:
 * - Real-time eve.json log file monitoring and parsing
 * - Standardized SOAR event publishing with JSON message format
 * - Module registration and heartbeat management
 * - Command queue listener for orchestration
 */
public class SuricataModule {
    
    private static final String MODULE_ID = "suricata_nids_01";
    private static final String MODULE_NAME = "Suricata NIDS Module";
    private static final String MODULE_TYPE = "network_security";
    private static final String COMMAND_QUEUE = "suricata_commands_queue";
    
    private static final String RABBITMQ_HOST = "10.141.39.34";
    private static final int RABBITMQ_PORT = 5672;
    private static final String RABBITMQ_USER = "user";
    private static final String RABBITMQ_PASSWORD = "password";
    private static final String ALERTS_QUEUE = "alerts_queue";
    
    private static final String EVE_JSON_PATH = "/var/log/suricata/eve.json";
    
    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Gson gson = new Gson();
    
    private static ScheduledExecutorService scheduler;
    private static ExecutorService fileWatcher;
    private static volatile boolean running = true;
    
    public static void main(String[] args) {
        printBanner();
        
        try {
            // Check if eve.json exists
            if (!Files.exists(Paths.get(EVE_JSON_PATH))) {
                System.err.println("❌ Error: Suricata eve.json not found at: " + EVE_JSON_PATH);
                System.err.println("   Please ensure Suricata is installed and running.");
                System.exit(1);
            }
            
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(RABBITMQ_HOST);
            factory.setPort(RABBITMQ_PORT);
            factory.setUsername(RABBITMQ_USER);
            factory.setPassword(RABBITMQ_PASSWORD);
            
            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();
            
            // Declare queues
            channel.queueDeclare(ALERTS_QUEUE, true, false, false, null);
            channel.queueDeclare(COMMAND_QUEUE, true, false, false, null);
            
            System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST);
            
            // Step 1: Register module
            sendRegistration(channel);
            Thread.sleep(1000);
            
            // Step 2: Start heartbeat sender
            // startHeartbeats(channel);
            
            // Step 3: Start command listener
            startCommandListener(channel);
            
            // Step 4: Start eve.json monitoring
            startEveJsonMonitoring(channel);
            
            System.out.println("\n✅ Suricata module running. Press Ctrl+C to stop.\n");
            
            // Keep running until interrupted
            while (running) {
                Thread.sleep(1000);
            }
            
            // Cleanup
            scheduler.shutdown();
            fileWatcher.shutdown();
            channel.close();
            connection.close();
            
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║  Suricata NIDS Module - Real Parsing  ║");
        System.out.println("╚════════════════════════════════════════╝\n");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Module Type: " + MODULE_TYPE);
        System.out.println("Eve.json Path: " + EVE_JSON_PATH);
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
        capabilities.put("network_ids");
        capabilities.put("alert_generation");
        capabilities.put("packet_analysis");
        payload.put("capabilities", capabilities);
        
        payload.put("command_queue", COMMAND_QUEUE);
        payload.put("version", "1.0.0");
        
        JSONObject metadata = new JSONObject();
        metadata.put("vendor", "Suricata");
        metadata.put("log_source", EVE_JSON_PATH);
        payload.put("metadata", metadata);
        
        registration.put("payload", payload);
        
        channel.basicPublish("", ALERTS_QUEUE, null, registration.toString().getBytes("UTF-8"));
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
        
        System.out.println("💓 Heartbeat sender started (every 30 seconds)");
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
        payload.put("uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        
        heartbeat.put("payload", payload);
        
        channel.basicPublish("", ALERTS_QUEUE, null, heartbeat.toString().getBytes("UTF-8"));
        System.out.println("💓 Sent heartbeat");
    }
    
    private static void startCommandListener(Channel channel) {
        new Thread(() -> {
            try {
                System.out.println("🎯 Command listener started on: " + COMMAND_QUEUE);
                
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
    
    /**
     * Starts real-time monitoring of Suricata eve.json log file
     */
    private static void startEveJsonMonitoring(Channel channel) {
        fileWatcher = Executors.newSingleThreadExecutor();
        
        fileWatcher.submit(() -> {
            System.out.println("📡 Starting real-time eve.json monitoring...\n");
            
            try {
                Path eveJsonPath = Paths.get(EVE_JSON_PATH);
                Path dir = eveJsonPath.getParent();
                
                try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                    dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                    
                    // Start from current end of file - only process NEW alerts
                    long lastPosition = Files.size(eveJsonPath);
                    System.out.println("✅ Monitoring started from position: " + lastPosition);
                    
                    while (running && !Thread.currentThread().isInterrupted()) {
                        WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                        
                        if (key == null) {
                            continue;
                        }
                        
                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path changed = (Path) event.context();
                            
                            if (changed.endsWith(eveJsonPath.getFileName())) {
                                try (RandomAccessFile raf = new RandomAccessFile(eveJsonPath.toFile(), "r")) {
                                    long currentSize = raf.length();
                                    
                                    if (currentSize > lastPosition) {
                                        raf.seek(lastPosition);
                                        
                                        // Read all new lines
                                        String line;
                                        while ((line = raf.readLine()) != null) {
                                            if (!line.trim().isEmpty()) {
                                                try {
                                                    parseEveJsonLine(line, channel);
                                                } catch (Exception e) {
                                                    System.err.println("❌ Error parsing line: " + e.getMessage());
                                                }
                                            }
                                        }
                                        
                                        lastPosition = raf.getFilePointer();
                                    } else if (currentSize < lastPosition) {
                                        // File was truncated or rotated
                                        System.out.println("⚠️  Eve.json file rotated. Starting from beginning.");
                                        lastPosition = 0;
                                    }
                                }
                            }
                        }
                        
                        key.reset();
                    }
                    
                } catch (InterruptedException e) {
                    System.out.println("ℹ️  Eve.json monitoring interrupted");
                    Thread.currentThread().interrupt();
                }
                
            } catch (Exception e) {
                System.err.println("❌ Failed to monitor eve.json file: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    /**
     * Parses a single line from Suricata eve.json log file
     * and publishes standardized alert if it's an alert event
     */
    private static void parseEveJsonLine(String jsonLine, Channel channel) {
        try {
            // Validate JSON structure before parsing
            if (!jsonLine.trim().startsWith("{") || !jsonLine.trim().endsWith("}")) {
                System.err.println("⚠️  Skipping malformed JSON line");
                return;
            }
            
            // Parse the eve.json line
            SuricataEveLog eveLog = gson.fromJson(jsonLine, SuricataEveLog.class);
            // Only process alert events
            if (!"alert".equals(eveLog.eventType)) {
                return;
            }
            
            // Validate essential fields
            if (eveLog.alert == null || eveLog.srcIp == null || eveLog.destIp == null) {
                System.err.println("⚠️  Eve.json alert missing required fields");
                return;
            }
            
            // Create standardized alert message from real eve.json data
            JSONObject alert = new JSONObject();
            alert.put("message_type", "alert");
            alert.put("event_id", UUID.randomUUID().toString());
            
            // Parse and format the timestamp
            String timestamp;
            try {
                DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ");
                OffsetDateTime odt = OffsetDateTime.parse(eveLog.timestamp, inputFormatter);
                timestamp = odt.format(DateTimeFormatter.ISO_INSTANT);
            } catch (Exception e) {
                // Fallback to current time if timestamp parsing fails
                timestamp = getCurrentManilaTime();
            }
            alert.put("timestamp", timestamp);
            
            alert.put("event_type", "alerts.network.suricata");
            alert.put("source_module", MODULE_NAME);
            
            // Create comprehensive payload from eve.json data
            JSONObject payload = new JSONObject();
            payload.put("alert_id", generateAlertId());
            payload.put("signature_id", String.valueOf(eveLog.alert.signatureId != null ? 
                    eveLog.alert.signatureId : 0));
            payload.put("signature", eveLog.alert.signature);
            payload.put("severity", mapSeverityLevel(eveLog.alert.severity != null ? 
                    eveLog.alert.severity : 3));
            payload.put("source_ip", eveLog.srcIp);
            payload.put("destination_ip", eveLog.destIp);
            payload.put("source_port", eveLog.srcPort != null ? eveLog.srcPort : 0);
            payload.put("destination_port", eveLog.destPort != null ? eveLog.destPort : 0);
            payload.put("protocol", eveLog.proto != null ? eveLog.proto : "UNKNOWN");
            
            // Determine category from signature or use provided category
            String category = eveLog.alert.category != null ?
                    eveLog.alert.category.toLowerCase().replace(" ", "_") :
                    categorizeFromSignature(eveLog.alert.signature);
            payload.put("category", category);
            payload.put("alert_type", category);
            
            // Calculate threat and confidence scores
            int severity = eveLog.alert.severity != null ? eveLog.alert.severity : 3;
            payload.put("threat_score", calculateThreatScore(severity, category));
            payload.put("confidence_score", 95); // High confidence for real alerts
            
            alert.put("payload", payload);
            
            // Publish the alert to RabbitMQ
            channel.basicPublish("", ALERTS_QUEUE, null, alert.toString().getBytes("UTF-8"));
            
            // Log with detailed information
            String logMessage = String.format(
                    "📤 Published NIDS alert: %s [Severity: %s, Priority: %d] {%s} %s:%d -> %s:%d",
                    eveLog.alert.signature,
                    payload.getString("severity"),
                    eveLog.alert.severity,
                    eveLog.proto,
                    eveLog.srcIp,
                    eveLog.srcPort != null ? eveLog.srcPort : 0,
                    eveLog.destIp,
                    eveLog.destPort != null ? eveLog.destPort : 0
            );
            System.out.println(logMessage);
            
        } catch (Exception e) {
            System.err.println("❌ Failed to parse eve.json line: " + e.getMessage());
        }
    }
    
    /**
     * Categorizes alert based on signature content
     */
    private static String categorizeFromSignature(String signature) {
        if (signature == null) return "unknown";
        
        String lower = signature.toLowerCase();
        
        if (lower.contains("malware") || lower.contains("trojan")) {
            return "malware";
        } else if (lower.contains("exploit") || lower.contains("cve-")) {
            return "exploit";
        } else if (lower.contains("scan") || lower.contains("recon")) {
            return "reconnaissance";
        } else if (lower.contains("sql") || lower.contains("injection")) {
            return "sql_injection";
        } else if (lower.contains("xss") || lower.contains("script")) {
            return "xss";
        } else if (lower.contains("dos") || lower.contains("ddos")) {
            return "denial_of_service";
        } else if (lower.contains("apt") || lower.contains("threat")) {
            return "apt_activity";
        } else if (lower.contains("c2") || lower.contains("command")) {
            return "c2_communication";
        } else if (lower.contains("ransomware")) {
            return "ransomware";
        } else {
            return "network_threat";
        }
    }
    
    // Utility Methods
    
    private static String generateAlertId() {
        return "SURI-" + System.currentTimeMillis() + "-" + 
               String.format("%04d", new Random().nextInt(10000));
    }
    
    private static String mapSeverityLevel(int level) {
        switch (level) {
            case 1: return "critical";
            case 2: return "high";
            case 3: return "medium";
            default: return "low";
        }
    }
    
    private static int calculateThreatScore(int severity, String category) {
        int baseScore = 0;
        switch (severity) {
            case 1: baseScore = 85; break;
            case 2: baseScore = 65; break;
            case 3: baseScore = 45; break;
            default: baseScore = 25;
        }
        
        // Adjust based on category
        if (category.contains("apt") || category.contains("ransomware")) {
            baseScore += 10;
        } else if (category.contains("malware") || category.contains("trojan")) {
            baseScore += 5;
        }
        
        return Math.min(100, baseScore);
    }
    
    private static String getCurrentManilaTime() {
        ZonedDateTime manilaTime = ZonedDateTime.now(MANILA_ZONE);
        return manilaTime.format(ISO_FORMATTER);
    }
    
    // Data structures for parsing Suricata eve.json
    
    /**
     * Data structure for parsing Suricata eve.json log entries
     */
    private static class SuricataEveLog {
        @SerializedName("event_type")
        String eventType;
        
        @SerializedName("timestamp")
        String timestamp;
        
        @SerializedName("src_ip")
        String srcIp;
        
        @SerializedName("dest_ip")
        String destIp;
        
        @SerializedName("src_port")
        Integer srcPort;
        
        @SerializedName("dest_port")
        Integer destPort;
        
        @SerializedName("proto")
        String proto;
        
        @SerializedName("alert")
        SuricataAlertInfo alert;
    }
    
    /**
     * Data structure for Suricata alert information within eve.json
     */
    private static class SuricataAlertInfo {
        @SerializedName("signature")
        String signature;
        
        @SerializedName("signature_id")
        Integer signatureId;
        
        @SerializedName("severity")
        Integer severity;
        
        @SerializedName("category")
        String category;
    }
}
