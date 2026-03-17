package com.nis1.thesis.udm;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * SuricataModule - User-Defined Module for Suricata NIDS Integration
 * 
 * Standalone Java process that:
 * - Registers itself with the ModuleRegistry via RabbitMQ
 * - Sends periodic heartbeats to maintain online status
 * - Monitors Suricata's eve.json log file in real-time
 * - Transforms Suricata alerts into standardized alerts.network.suricata events
 * - Listens for orchestration commands from the workflow engine
 * 
 * This module follows the JSON message format described in
 * SDK_Detailed_Context.md
 * and aligns with the ModuleRegistry & LifecycleManager architecture.
 */
public class SuricataModule {

    // --- Configuration File -------------------------------------------------

    private static final String CONFIG_PATH = "config/suricata-module.properties";

    // --- Module Identity (loaded from config) -------------------------------

    private static String MODULE_ID = "suricata_nids_01";
    private static String MODULE_NAME = "Suricata NIDS Module";
    private static String MODULE_TYPE = "network_security";
    private static String COMMAND_QUEUE = "suricata_commands_queue";
    private static String MODULE_CAPABILITIES = "network_ids,alert_generation,packet_analysis,threat_detection";

    // --- RabbitMQ Configuration (align with ModuleRegistry) -----------------

    private static String RABBITMQ_HOST = "localhost";
    private static int RABBITMQ_PORT = 5672;
    private static String RABBITMQ_USER = "user";
    private static String RABBITMQ_PASSWORD = "password";
    private static String WORKFLOW_QUEUE = "workflow_queue"; // UDM → ModuleRegistry

    // --- Suricata Configuration ---------------------------------------------

    private static String EVE_JSON_PATH = "/var/log/suricata/eve.json";

    // --- Heartbeat Configuration --------------------------------------------

    private static int HEARTBEAT_INTERVAL = 30; // seconds

    // --- Time ---------------------------------------------------------------

    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Gson gson = new Gson();

    // --- Runtime state ------------------------------------------------------

    private static ScheduledExecutorService scheduler;
    private static ExecutorService fileWatcher;
    private static volatile boolean running = true;
    private static long moduleStartTime = System.currentTimeMillis();

    public static void main(String[] args) {
        printBanner();

        // Load configuration from properties file
        loadConfig();

        try {
            // Wait for eve.json to appear (Suricata may not be running yet)
            if (!Files.exists(Paths.get(EVE_JSON_PATH))) {
                System.out.println("⏳ Suricata eve.json not found at: " + EVE_JSON_PATH);
                System.out.println("   Waiting for Suricata to start...");
                while (!Files.exists(Paths.get(EVE_JSON_PATH)) && running) {
                    Thread.sleep(10000); // Retry every 10 seconds
                }
                if (!running)
                    return;
                System.out.println("✅ Suricata eve.json found! Continuing startup...");
            }

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

            System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);

            // 1) Register module with ModuleRegistry
            sendRegistration(channel);
            Thread.sleep(1000);

            // 2) Start heartbeat sender
            startHeartbeats(channel);

            // 3) Start command listener
            startCommandListener(channel);

            // 4) Start eve.json monitoring
            startEveJsonMonitoring(channel);

            System.out.println("\n✅ SuricataModule is running. Press Ctrl+C to stop.\n");

            // Graceful shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Shutting down SuricataModule...");
                running = false;
                if (scheduler != null)
                    scheduler.shutdownNow();
                if (fileWatcher != null)
                    fileWatcher.shutdownNow();
            }));

            // Keep main thread alive
            while (running) {
                Thread.sleep(1000);
            }

            // Cleanup
            channel.close();
            connection.close();

        } catch (Exception e) {
            System.err.println("❌ SuricataModule fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (scheduler != null)
                scheduler.shutdownNow();
            if (fileWatcher != null)
                fileWatcher.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------
    // Registration & Heartbeat
    // ---------------------------------------------------------------------

    private static void sendRegistration(Channel channel) throws IOException {
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

        // Capabilities are used for routing workflow commands
        JSONArray capabilities = new JSONArray();
        for (String cap : MODULE_CAPABILITIES.split(",")) {
            capabilities.put(cap.trim());
        }
        payload.put("capabilities", capabilities);

        payload.put("command_queue", COMMAND_QUEUE);
        payload.put("version", "2.0.0");

        JSONObject metadata = new JSONObject();
        metadata.put("vendor", "Suricata");
        metadata.put("log_source", EVE_JSON_PATH);
        metadata.put("detection_type", "network_ids");
        payload.put("metadata", metadata);

        registration.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                registration.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("📝 Sent SuricataModule registration to ModuleRegistry");
    }

    private static void startHeartbeats(Channel channel) {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeat(channel);
            } catch (Exception e) {
                System.err.println("❌ Heartbeat failed: " + e.getMessage());
            }
        }, 5, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);

        System.out.println("💓 Heartbeat sender started (every " + HEARTBEAT_INTERVAL + " seconds)");
    }

    private static void sendHeartbeat(Channel channel) throws IOException {
        JSONObject heartbeat = new JSONObject();
        heartbeat.put("message_type", "heartbeat");
        heartbeat.put("event_id", "hb-" + UUID.randomUUID().toString());
        heartbeat.put("timestamp", getCurrentManilaTime());
        heartbeat.put("event_type", "system.module.heartbeat");
        heartbeat.put("source_module", MODULE_NAME);

        JSONObject payload = new JSONObject();
        payload.put("module_id", MODULE_ID);
        payload.put("status", "online");
        payload.put("uptime_seconds", (System.currentTimeMillis() - moduleStartTime) / 1000);

        heartbeat.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                heartbeat.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("💓 Sent heartbeat");
    }

    // ---------------------------------------------------------------------
    // Command Listener
    // ---------------------------------------------------------------------

    private static void startCommandListener(Channel channel) {
        new Thread(() -> {
            try {
                System.out.println("🎯 Command listener started on: " + COMMAND_QUEUE);

                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    try {
                        JSONObject json = new JSONObject(message);

                        System.out.println("\n🎯 Received command:");
                        System.out.println(json.toString(2));

                        // Process command based on event_type
                        String eventType = json.optString("event_type", "");
                        handleCommand(eventType, json, channel);

                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    } catch (Exception e) {
                        System.err.println("❌ Error processing command: " + e.getMessage());
                        channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                    }
                };

                channel.basicConsume(COMMAND_QUEUE, false, deliverCallback, consumerTag -> {
                });

            } catch (Exception e) {
                System.err.println("❌ Command listener error: " + e.getMessage());
            }
        }).start();
    }

    private static void handleCommand(String eventType, JSONObject command, Channel channel) {
        // Handle different command types
        switch (eventType) {
            case "command.suricata.reload_rules":
                System.out.println("📋 Processing reload_rules command");
                // In real implementation, trigger Suricata rule reload
                break;
            case "command.suricata.status":
                System.out.println("📋 Processing status command");
                // Return module status
                break;
            default:
                System.out.println("⚠️  Unknown command type: " + eventType);
        }
    }

    // ---------------------------------------------------------------------
    // Eve.json Monitoring
    // ---------------------------------------------------------------------

    /**
     * Starts real-time monitoring of Suricata eve.json log file
     */
    private static void startEveJsonMonitoring(Channel channel) {
        fileWatcher = Executors.newSingleThreadExecutor();

        fileWatcher.submit(() -> {
            System.out.println("📡 Starting real-time eve.json monitoring...");
            System.out.println("   Monitoring: " + EVE_JSON_PATH + "\n");

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
                System.err.println("⚠️  Eve.json alert missing required fields - alert:"
                        + (eveLog.alert != null ? "OK" : "NULL")
                        + " srcIp:" + (eveLog.srcIp != null ? eveLog.srcIp : "NULL")
                        + " destIp:" + (eveLog.destIp != null ? eveLog.destIp : "NULL"));
                return;
            }

            // Create standardized alert message
            publishSuricataAlert(eveLog, channel);

        } catch (Exception e) {
            System.err.println("❌ Failed to parse eve.json line: " + e.getMessage());
        }
    }

    /**
     * Publishes standardized alerts.network.suricata event
     */
    private static void publishSuricataAlert(SuricataEveLog eveLog, Channel channel) throws IOException {
        // Create standardized alert message
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
            timestamp = Instant.now().toString();
        }
        alert.put("timestamp", timestamp);

        alert.put("event_type", "alerts.network.suricata");
        alert.put("source_module", MODULE_NAME);

        // Create payload using SuricataAlertData
        SuricataAlertData payloadData = new SuricataAlertData();
        payloadData.setAlertId(generateAlertId());
        payloadData.setSignatureId(String.valueOf(eveLog.alert.signatureId != null ? eveLog.alert.signatureId : 0));
        payloadData.setSignature(eveLog.alert.signature);

        // Map severity
        int severityLevel = eveLog.alert.severity != null ? eveLog.alert.severity : 3;
        payloadData.setSeverity(mapSeverityLevel(severityLevel));

        // Network information
        payloadData.setSourceIp(eveLog.srcIp);
        payloadData.setDestinationIp(eveLog.destIp);
        payloadData.setSourcePort(eveLog.srcPort != null ? eveLog.srcPort : 0);
        payloadData.setDestinationPort(eveLog.destPort != null ? eveLog.destPort : 0);
        payloadData.setProtocol(eveLog.proto != null ? eveLog.proto : "UNKNOWN");

        // Determine category
        String category = eveLog.alert.category != null ? eveLog.alert.category.toLowerCase().replace(" ", "_")
                : categorizeFromSignature(eveLog.alert.signature);
        payloadData.setCategory(category);
        payloadData.setAlertType(category);

        // Calculate threat and confidence scores
        payloadData.setThreatScore(calculateThreatScore(severityLevel, category));
        payloadData.setConfidenceScore(95); // High confidence for real alerts

        // Optional fields
        if (eveLog.action != null)
            payloadData.setAction(eveLog.action);
        if (eveLog.flowId != null)
            payloadData.setFlowId(String.valueOf(eveLog.flowId));

        // Convert to JSON using Gson for proper serialization
        String payloadJson = gson.toJson(payloadData);
        alert.put("payload", new JSONObject(payloadJson));

        // Publish the alert to workflow_queue (ModuleRegistry will forward to
        // alerts_queue)
        channel.basicPublish("", WORKFLOW_QUEUE, null,
                alert.toString().getBytes(StandardCharsets.UTF_8));

        // Log with detailed information
        String logMessage = String.format(
                "📤 Published NIDS alert: %s [Severity: %s, Priority: %d] {%s} %s:%d -> %s:%d",
                eveLog.alert.signature,
                payloadData.getSeverity(),
                severityLevel,
                eveLog.proto,
                eveLog.srcIp,
                eveLog.srcPort != null ? eveLog.srcPort : 0,
                eveLog.destIp,
                eveLog.destPort != null ? eveLog.destPort : 0);
        System.out.println(logMessage);
    }

    // ---------------------------------------------------------------------
    // Utility Methods
    // ---------------------------------------------------------------------

    /**
     * Categorizes alert based on signature content
     */
    private static String categorizeFromSignature(String signature) {
        if (signature == null)
            return "unknown";

        String lower = signature.toLowerCase();

        if (lower.contains("dns") && (lower.contains("amplification") || lower.contains("dns amp"))) {
            return "dns_amplification";
        } else if (lower.contains("icmp") && (lower.contains("flood") || lower.contains("ping"))) {
            return "icmp_flood";
        } else if (lower.contains("arp") && (lower.contains("spoof") || lower.contains("poison"))) {
            return "arp_spoofing";
        }

        if (lower.contains("wannacry") || lower.contains("eternalblue") || lower.contains("ms17-010")
                || lower.contains("doublepulsar")) {
            return "ransomware";
        } else if (lower.contains("malware") || lower.contains("trojan")) {
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

    private static String generateAlertId() {
        return "SURI-" + System.currentTimeMillis() + "-" +
                String.format("%04d", new Random().nextInt(10000));
    }

    private static String mapSeverityLevel(int level) {
        switch (level) {
            case 1:
                return "critical";
            case 2:
                return "high";
            case 3:
                return "medium";
            default:
                return "low";
        }
    }

    private static int calculateThreatScore(int severity, String category) {
        int baseScore = 0;
        switch (severity) {
            case 1:
                baseScore = 85;
                break;
            case 2:
                baseScore = 65;
                break;
            case 3:
                baseScore = 45;
                break;
            default:
                baseScore = 25;
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

    private static void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);

            // RabbitMQ configuration
            RABBITMQ_HOST = props.getProperty("rabbitmq.host", RABBITMQ_HOST);
            RABBITMQ_PORT = Integer.parseInt(props.getProperty("rabbitmq.port", String.valueOf(RABBITMQ_PORT)));
            RABBITMQ_USER = props.getProperty("rabbitmq.user", RABBITMQ_USER);
            RABBITMQ_PASSWORD = props.getProperty("rabbitmq.password", RABBITMQ_PASSWORD);
            WORKFLOW_QUEUE = props.getProperty("rabbitmq.workflow_queue", WORKFLOW_QUEUE);

            // Suricata configuration
            EVE_JSON_PATH = props.getProperty("suricata.eve_json_path", EVE_JSON_PATH);

            // Module identity
            MODULE_ID = props.getProperty("module.id", MODULE_ID);
            MODULE_NAME = props.getProperty("module.name", MODULE_NAME);
            MODULE_TYPE = props.getProperty("module.type", MODULE_TYPE);
            COMMAND_QUEUE = props.getProperty("module.command_queue", COMMAND_QUEUE);
            MODULE_CAPABILITIES = props.getProperty("module.capabilities", MODULE_CAPABILITIES);

            // Heartbeat configuration
            HEARTBEAT_INTERVAL = Integer.parseInt(props.getProperty("module.heartbeat_interval",
                    String.valueOf(HEARTBEAT_INTERVAL)));

            System.out.println("🔧 Loaded SuricataModule config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("⚠️  Could not load SuricataModule config (using defaults): " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║  Suricata User-Defined Module (UDM)   ║");
        System.out.println("║  Network IDS Integration v2.0          ║");
        System.out.println("╚════════════════════════════════════════╝\n");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Module Type: " + MODULE_TYPE);
        System.out.println("Command Queue: " + COMMAND_QUEUE + "\n");
    }

    // ---------------------------------------------------------------------
    // Data structures for parsing Suricata eve.json
    // ---------------------------------------------------------------------

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

        @SerializedName("flow_id")
        Long flowId;

        @SerializedName("action")
        String action;
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
