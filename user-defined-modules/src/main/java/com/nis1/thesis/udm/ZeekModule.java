package com.nis1.thesis.udm;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZeekModule - User-Defined Module for Zeek NSM Integration
 * 
 * Standalone Java process that:
 * - Registers itself with the ModuleRegistry via RabbitMQ
 * - Sends periodic heartbeats to maintain online status
 * - Monitors Zeek's notice.log file in real-time
 * - Transforms Zeek notices into standardized alerts.network.zeek events
 * - Listens for orchestration commands from the workflow engine
 * 
 * This module follows the JSON message format described in
 * SDK_Detailed_Context.md
 * and aligns with the ModuleRegistry & LifecycleManager architecture.
 */
public class ZeekModule {

    // --- Configuration File -------------------------------------------------

    private static final String CONFIG_PATH = "config/zeek-module.properties";

    // --- Module Identity (loaded from config) -------------------------------

    private static String MODULE_ID = "zeek_nsm_01";
    private static String MODULE_NAME = "Zeek NSM Module";
    private static String MODULE_TYPE = "network_security";
    private static String COMMAND_QUEUE = "zeek_commands_queue";
    private static String MODULE_CAPABILITIES = "network_monitoring,protocol_analysis,alert_generation,threat_detection";

    // --- RabbitMQ Configuration (align with ModuleRegistry) -----------------

    private static String RABBITMQ_HOST = "localhost";
    private static int RABBITMQ_PORT = 5672;
    private static String RABBITMQ_USER = "user";
    private static String RABBITMQ_PASSWORD = "password";
    private static String WORKFLOW_QUEUE = "workflow_queue"; // UDM → ModuleRegistry

    // --- Zeek Configuration -------------------------------------------------

    private static String NOTICE_LOG_PATH = "/opt/zeek/logs/current/notice.log";
    private static String SMB_MAPPING_LOG_PATH = "/opt/zeek/logs/current/smb_mapping.log";

    // --- Heartbeat Configuration --------------------------------------------

    private static int HEARTBEAT_INTERVAL = 30; // seconds

    // --- Time ---------------------------------------------------------------

    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // --- Zeek log field separator -------------------------------------------
    private static final String FIELD_SEPARATOR = "\t";

    // --- Runtime state ------------------------------------------------------

    private static ScheduledExecutorService scheduler;
    private static ExecutorService fileWatcher;
    private static volatile boolean running = true;
    private static long moduleStartTime = System.currentTimeMillis();

    // --- Zeek log column indices (based on standard notice.log format) ------
    private static int COL_TS = 0;
    private static int COL_UID = 1;
    private static int COL_ID_ORIG_H = 2; // Source IP
    private static int COL_ID_ORIG_P = 3; // Source Port
    private static int COL_ID_RESP_H = 4; // Destination IP
    private static int COL_ID_RESP_P = 5; // Destination Port
    private static int COL_PROTO = 7;
    private static int COL_NOTE = 8; // Notice type (e.g., Scan::Port_Scan)
    private static int COL_MSG = 9; // Notice message
    private static int COL_SUB = 10; // Sub-message
    private static int COL_SRC = 11; // Source (if different from id.orig_h)
    private static int COL_ACTIONS = 16; // Actions taken

    public static void main(String[] args) {
        printBanner();

        // Load configuration from properties file
        loadConfig();

        try {
            // Wait for notice.log to appear (Zeek may not be running yet)
            if (!Files.exists(Paths.get(NOTICE_LOG_PATH))) {
                System.out.println("⏳ Zeek notice.log not found at: " + NOTICE_LOG_PATH);
                System.out.println("   Waiting for Zeek to start...");
                while (!Files.exists(Paths.get(NOTICE_LOG_PATH)) && running) {
                    Thread.sleep(10000); // Retry every 10 seconds
                }
                if (!running)
                    return;
                System.out.println("✅ Zeek notice.log found! Continuing startup...");
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

            // 4) Start Zeek logs monitoring
            startZeekLogsMonitoring(channel);

            System.out.println("\n✅ ZeekModule is running. Press Ctrl+C to stop.\n");

            // Graceful shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Shutting down ZeekModule...");
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
            System.err.println("❌ ZeekModule fatal error: " + e.getMessage());
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
        payload.put("version", "1.0.0");

        JSONObject metadata = new JSONObject();
        metadata.put("vendor", "Zeek");
        metadata.put("log_source", NOTICE_LOG_PATH);
        metadata.put("detection_type", "network_nsm");
        payload.put("metadata", metadata);

        registration.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                registration.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("📝 Sent ZeekModule registration to ModuleRegistry");
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
            case "command.zeek.reload_scripts":
                System.out.println("📋 Processing reload_scripts command");
                // In real implementation, trigger Zeek script reload
                break;
            case "command.zeek.status":
                System.out.println("📋 Processing status command");
                // Return module status
                break;
            default:
                System.out.println("⚠️  Unknown command type: " + eventType);
        }
    }

    // ---------------------------------------------------------------------
    // Notice.log Monitoring
    // ---------------------------------------------------------------------

    /**
     * Starts real-time monitoring of Zeek log files
     */
    private static void startZeekLogsMonitoring(Channel channel) {
        fileWatcher = Executors.newFixedThreadPool(2);

        fileWatcher.submit(() -> monitorFile(NOTICE_LOG_PATH, channel, ZeekModule::parseNoticeLine));
        fileWatcher.submit(() -> monitorFile(SMB_MAPPING_LOG_PATH, channel, ZeekModule::parseSmbMappingLine));
    }

    private static void monitorFile(String filePath, Channel channel,
            java.util.function.BiConsumer<String, Channel> parser) {
        System.out.println("📡 Starting real-time monitoring: " + filePath);

        try {
            Path targetPath = Paths.get(filePath);
            Path dir = targetPath.getParent();

            if (!Files.exists(targetPath)) {
                System.out.println("⚠️  File not found (yet): " + filePath);
            }

            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);

                long lastPosition = Files.exists(targetPath) ? Files.size(targetPath) : 0;
                System.out.println(
                        "✅ Monitoring " + targetPath.getFileName() + " started from position: " + lastPosition);

                while (running && !Thread.currentThread().isInterrupted()) {
                    WatchKey key = watchService.poll(1, TimeUnit.SECONDS);

                    if (key == null) {
                        continue;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();

                        if (changed.endsWith(targetPath.getFileName())) {
                            try (RandomAccessFile raf = new RandomAccessFile(targetPath.toFile(), "r")) {
                                long currentSize = raf.length();

                                if (currentSize > lastPosition) {
                                    raf.seek(lastPosition);

                                    String line;
                                    while ((line = raf.readLine()) != null) {
                                        if (!line.trim().isEmpty() && !line.startsWith("#")) {
                                            try {
                                                parser.accept(line, channel);
                                            } catch (Exception e) {
                                                System.err.println("❌ Error parsing line in " + targetPath.getFileName()
                                                        + ": " + e.getMessage());
                                            }
                                        }
                                    }

                                    lastPosition = raf.getFilePointer();
                                } else if (currentSize < lastPosition) {
                                    System.out.println("⚠️  " + targetPath.getFileName()
                                            + " file rotated. Starting from beginning.");
                                    lastPosition = 0;
                                }
                            } catch (Exception e) {
                                // File might be temporarily locked, ignore and try on next poll
                            }
                        }
                    }

                    key.reset();
                }

            } catch (InterruptedException e) {
                System.out.println("ℹ️  " + targetPath.getFileName() + " monitoring interrupted");
                Thread.currentThread().interrupt();
            }

        } catch (Exception e) {
            System.err.println("❌ Failed to monitor " + filePath + ": " + e.getMessage());
        }
    }

    /**
     * Parses a single line from Zeek notice.log file
     * and publishes standardized alert if it's a ransomware-related notice
     */
    private static void parseNoticeLine(String line, Channel channel) {
        try {
            // Zeek logs are tab-separated
            String[] fields = line.split(FIELD_SEPARATOR, -1);

            if (fields.length < 10) {
                return; // Not enough fields
            }

            // Extract notice type
            String noteType = fields.length > COL_NOTE ? fields[COL_NOTE] : "-";
            String message = fields.length > COL_MSG ? fields[COL_MSG] : "";
            String subMessage = fields.length > COL_SUB ? fields[COL_SUB] : "";

            // Check if this is a ransomware-related notice
            if (!isRansomwareNotice(noteType, message, subMessage)) {
                return;
            }

            // Parse network information
            String srcIp = fields.length > COL_ID_ORIG_H ? parseField(fields[COL_ID_ORIG_H]) : null;
            String srcPort = fields.length > COL_ID_ORIG_P ? parseField(fields[COL_ID_ORIG_P]) : null;
            String dstIp = fields.length > COL_ID_RESP_H ? parseField(fields[COL_ID_RESP_H]) : null;
            String dstPort = fields.length > COL_ID_RESP_P ? parseField(fields[COL_ID_RESP_P]) : null;
            String proto = fields.length > COL_PROTO ? parseField(fields[COL_PROTO]) : "unknown";
            String timestamp = fields.length > COL_TS ? fields[COL_TS] : null;

            // Fallback to COL_SRC if id.orig_h is empty
            if (srcIp == null || srcIp.isEmpty()) {
                srcIp = fields.length > COL_SRC ? parseField(fields[COL_SRC]) : null;
            }

            if (srcIp == null || srcIp.isEmpty()) {
                System.err.println("⚠️  Zeek notice missing source IP");
                return;
            }

            // Publish alert
            publishZeekAlert(noteType, message, subMessage, srcIp, srcPort, dstIp, dstPort, proto, timestamp, channel);

        } catch (Exception e) {
            System.err.println("❌ Failed to parse notice.log line: " + e.getMessage());
        }
    }

    /**
     * Parses a single line from Zeek smb_mapping.log file
     * and publishes standardized alert if it represents EternalBlue/WannaCry
     * lateral movement
     */
    private static void parseSmbMappingLine(String line, Channel channel) {
        try {
            String[] fields = line.split(FIELD_SEPARATOR, -1);

            if (fields.length < 6) {
                return;
            }

            String ts = fields[0];
            String srcIp = parseField(fields[2]);
            String srcPort = parseField(fields[3]);
            String dstIp = parseField(fields[4]);
            String dstPort = parseField(fields[5]);
            // path is typically field 6
            String path = fields.length > 6 ? parseField(fields[6]) : "-";

            if (srcIp == null || dstIp == null) {
                return;
            }

            // Detect WannaCry / EternalBlue anomalous IPC$ traffic
            if (path != null && path.contains("IPC$")) {
                String noteType = "EternalBlue_Exploit_Attempt";
                String message = "Suspicious SMB IPC$ connection (Possible lateral movement)";
                String subMessage = "Path: " + path;

                publishZeekAlert(noteType, message, subMessage, srcIp, srcPort, dstIp, dstPort, "SMB", ts, channel);
            }

        } catch (Exception e) {
            System.err.println("❌ Failed to parse smb_mapping.log line: " + e.getMessage());
        }
    }

    /**
     * Check if the notice is ransomware-related
     */
    /**
     * Check if the notice is ransomware-related
     */
    private static boolean isRansomwareNotice(String noteType, String message, String subMessage) {
        String combined = (noteType + " " + message + " " + subMessage).toLowerCase();

        // 1. Whitelist (High Precision) - Ignore known benign traffic
        if (isWhitelisted(combined)) {
            System.out.println("⚪ Ignored whitelisted notice: " + combined);
            return false;
        }

        // 2. High Confidence Signatures (Ransomware specific)
        if (combined.contains("ransomware") ||
                combined.contains("wannacry") ||
                combined.contains("petya") ||
                combined.contains("dharma") ||
                combined.contains("ryuk")) {
            return true;
        }

        // 3. Behavioral Patterns (Requires more context, but acceptable for this thesis
        // scope)
        // Only trigger if specifically categorized as 'Actionable' or 'High' importance
        if ((combined.contains("smb") && combined.contains("eternalblue")) ||
                (combined.contains("c2") && combined.contains("command and control"))) {
            return true;
        }

        // 4. Reduce FP from generic scans (only trigger if "Scan::Port_Scan" AND
        // involves high port count or sensitive ports)
        // In this simple implementation, we'll keep "Scan::Port_Scan" but rely on
        // whitelist to filter out authorized scanners
        if (combined.contains("scan::port_scan") && !combined.contains("local")) {
            return true;
        }

        return false;
    }

    /**
     * Check if the notice matches whitelisted patterns
     */
    private static boolean isWhitelisted(String combined) {
        return combined.contains("google.com") ||
                combined.contains("research") ||
                combined.contains("paper") ||
                combined.contains("pdf") || // Files often named "ransomware_analysis.pdf"
                combined.contains("wikipedia") ||
                combined.contains("ubuntu-archive"); // Common substantial traffic
    }

    /**
     * Parse Zeek field (handle "-" as null/empty)
     */
    private static String parseField(String field) {
        if (field == null || field.equals("-") || field.isEmpty()) {
            return null;
        }
        return field.trim();
    }

    /**
     * Publishes standardized alerts.network.zeek event
     */
    private static void publishZeekAlert(String noteType, String message, String subMessage,
            String srcIp, String srcPort, String dstIp, String dstPort,
            String proto, String zeekTimestamp, Channel channel) throws IOException {

        // Create standardized alert message
        JSONObject alert = new JSONObject();
        alert.put("message_type", "alert");
        alert.put("event_id", UUID.randomUUID().toString());

        // Parse Zeek epoch timestamp
        String timestamp;
        try {
            if (zeekTimestamp != null && !zeekTimestamp.equals("-")) {
                double epochSeconds = Double.parseDouble(zeekTimestamp);
                Instant instant = Instant.ofEpochSecond((long) epochSeconds,
                        (long) ((epochSeconds - (long) epochSeconds) * 1_000_000_000));
                timestamp = instant.toString();
            } else {
                timestamp = Instant.now().toString();
            }
        } catch (Exception e) {
            timestamp = Instant.now().toString();
        }
        alert.put("timestamp", timestamp);

        alert.put("event_type", "alerts.network.zeek");
        alert.put("source_module", MODULE_NAME);

        // Build payload
        JSONObject payload = new JSONObject();
        payload.put("alert_id", generateAlertId());
        payload.put("note_type", noteType);
        payload.put("signature", message);
        payload.put("sub_message", subMessage);

        // Determine severity based on note type
        String severity = determineSeverity(noteType, message);
        payload.put("severity", severity);

        // Network information
        payload.put("source_ip", srcIp);
        if (srcPort != null)
            payload.put("source_port", Integer.parseInt(srcPort));
        if (dstIp != null)
            payload.put("destination_ip", dstIp);
        if (dstPort != null)
            payload.put("destination_port", Integer.parseInt(dstPort));
        payload.put("protocol", proto != null ? proto.toUpperCase() : "UNKNOWN");

        // Category - always ransomware for this module
        String category = categorizeFromNote(noteType, message);
        payload.put("category", category);
        payload.put("alert_type", category);

        // Calculate scores
        payload.put("threat_score", calculateThreatScore(severity, category));
        payload.put("confidence_score", 90); // Slightly lower than Suricata since Zeek is behavioral

        alert.put("payload", payload);

        // Publish the alert to workflow_queue
        channel.basicPublish("", WORKFLOW_QUEUE, null,
                alert.toString().getBytes(StandardCharsets.UTF_8));

        // Log
        String logMessage = String.format(
                "📤 Published Zeek alert: %s [Severity: %s] %s -> %s",
                noteType,
                severity,
                srcIp,
                dstIp != null ? dstIp : "N/A");
        System.out.println(logMessage);
    }

    // ---------------------------------------------------------------------
    // Utility Methods
    // ---------------------------------------------------------------------

    /**
     * Determine severity based on Zeek notice type
     */
    private static String determineSeverity(String noteType, String message) {
        String combined = (noteType + " " + message).toLowerCase();

        if (combined.contains("ransomware") ||
                combined.contains("malware") ||
                combined.contains("eternalblue") ||
                combined.contains("c2")) {
            return "critical";
        } else if (combined.contains("scan::port_scan") ||
                combined.contains("intel::notice") ||
                combined.contains("lateral")) {
            return "high";
        } else if (combined.contains("scan::address_scan") ||
                combined.contains("smb")) {
            return "medium";
        }
        return "low";
    }

    /**
     * Categorize from Zeek notice type
     */
    private static String categorizeFromNote(String noteType, String message) {
        String combined = (noteType + " " + message).toLowerCase();

        if (combined.contains("ransomware") ||
                combined.contains("wannacry") ||
                combined.contains("petya") ||
                combined.contains("dharma") ||
                combined.contains("ryuk") ||
                combined.contains("eternalblue") ||
                combined.contains("ms17-010") ||
                combined.contains("smb_mapping_event")) {
            return "ransomware";
        } else if (combined.contains("malware") || combined.contains("trojan")) {
            return "malware";
        } else if (combined.contains("c2") || combined.contains("command and control")) {
            return "c2_communication";
        } else if (combined.contains("scan::port_scan") && combined.contains("syn")) {
            return "tcp_syn_scan";
        } else if (combined.contains("scan::port_scan") || combined.contains("scan::address_scan")) {
            return "nmap_recon";
        } else if (combined.contains("lateral")) {
            return "lateral_movement";
        } else if (combined.contains("smb")) {
            return "exploit";
        }
        return "ransomware"; // Default to ransomware for this focused module
    }

    private static String generateAlertId() {
        return "ZEEK-" + System.currentTimeMillis() + "-" +
                String.format("%04d", new Random().nextInt(10000));
    }

    private static int calculateThreatScore(String severity, String category) {
        int baseScore = 0;
        switch (severity) {
            case "critical":
                baseScore = 90;
                break;
            case "high":
                baseScore = 70;
                break;
            case "medium":
                baseScore = 50;
                break;
            default:
                baseScore = 30;
        }

        // Boost for ransomware
        if (category.equals("ransomware") || category.equals("c2_communication")) {
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

            // Zeek configuration
            NOTICE_LOG_PATH = props.getProperty("zeek.notice_log_path", NOTICE_LOG_PATH);
            SMB_MAPPING_LOG_PATH = props.getProperty("zeek.smb_mapping_log_path", SMB_MAPPING_LOG_PATH);

            // Module identity
            MODULE_ID = props.getProperty("module.id", MODULE_ID);
            MODULE_NAME = props.getProperty("module.name", MODULE_NAME);
            MODULE_TYPE = props.getProperty("module.type", MODULE_TYPE);
            COMMAND_QUEUE = props.getProperty("module.command_queue", COMMAND_QUEUE);
            MODULE_CAPABILITIES = props.getProperty("module.capabilities", MODULE_CAPABILITIES);

            // Heartbeat configuration
            HEARTBEAT_INTERVAL = Integer.parseInt(props.getProperty("module.heartbeat_interval",
                    String.valueOf(HEARTBEAT_INTERVAL)));

            System.out.println("🔧 Loaded ZeekModule config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("⚠️  Could not load ZeekModule config (using defaults): " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║  Zeek User-Defined Module (UDM)        ║");
        System.out.println("║  Network Security Monitor v1.0         ║");
        System.out.println("╚════════════════════════════════════════╝\n");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Module Type: " + MODULE_TYPE);
        System.out.println("Command Queue: " + COMMAND_QUEUE + "\n");
    }
}
