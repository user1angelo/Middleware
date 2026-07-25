package com.nis1.thesis.udm;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * MaltrailModule - User-Defined Module for Maltrail Threat-Intelligence Integration
 *
 * Standalone Java process that:
 * - Registers itself with the ModuleRegistry via RabbitMQ
 * - Sends periodic heartbeats to maintain online status
 * - Listens for Maltrail's Logstash-style UDP JSON events in real-time
 * - Transforms Maltrail trail matches into standardized alerts.network.maltrail events
 * - Listens for orchestration commands from the workflow engine
 *
 * Mirrors SuricataModule's standalone-process shape, but ingests via a UDP socket
 * (matching Maltrail's push-based LOGSTASH_SERVER delivery) instead of file-tailing,
 * since Maltrail's local log format is a different, unstructured text format.
 *
 * Zero changes were required to CoreSystemApi, WorkflowMatcher, WorkflowEngine, or
 * OpenDaylightModule to add this module - it publishes to the same workflow_queue
 * via the same basicPublish("", queueName, ...) shape SuricataModule already uses,
 * and MaltrailAlertData mirrors SuricataAlertData's field names so the existing
 * condition-matching engine works unmodified.
 */
public class MaltrailModule {

    // --- Configuration File -------------------------------------------------

    private static final String CONFIG_PATH = "config/maltrail-module.properties";

    // --- Module Identity (loaded from config) -------------------------------

    private static String MODULE_ID = "maltrail-module";
    private static String MODULE_NAME = "Maltrail UDM";
    private static String MODULE_TYPE = "network_security";
    private static String COMMAND_QUEUE = "maltrail-module_commands_queue";
    private static String MODULE_CAPABILITIES = "network_ids,threat_intel,alert_generation";

    // --- RabbitMQ Configuration (align with ModuleRegistry) -----------------

    private static String RABBITMQ_HOST = "localhost";
    private static int RABBITMQ_PORT = 5672;
    private static String RABBITMQ_USER = "user";
    private static String RABBITMQ_PASSWORD = "password";
    private static String WORKFLOW_QUEUE = "workflow_queue"; // UDM -> ModuleRegistry

    // --- Maltrail Configuration ----------------------------------------------

    private static int MALTRAIL_UDP_PORT = 8481;

    // --- Heartbeat Configuration --------------------------------------------

    private static int HEARTBEAT_INTERVAL = 30; // seconds

    // --- Time ---------------------------------------------------------------

    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Gson gson = new Gson();

    // --- Runtime state ------------------------------------------------------

    private static ScheduledExecutorService scheduler;
    private static ExecutorService udpListenerExecutor;
    private static volatile boolean running = true;
    private static long moduleStartTime = System.currentTimeMillis();
    private static volatile DatagramSocket udpSocket;

    public static void main(String[] args) {
        printBanner();

        // Load configuration from properties file
        loadConfig();

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

            System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);

            // 1) Register module with ModuleRegistry
            sendRegistration(channel);
            Thread.sleep(1000);

            // 2) Start heartbeat sender
            startHeartbeats(channel);

            // 3) Start command listener
            startCommandListener(channel);

            // 4) Start Maltrail UDP listener
            startMaltrailUdpListener(channel);

            System.out.println("\n✅ MaltrailModule is running. Press Ctrl+C to stop.\n");

            // Graceful shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Shutting down MaltrailModule...");
                running = false;
                if (scheduler != null)
                    scheduler.shutdownNow();
                if (udpListenerExecutor != null)
                    udpListenerExecutor.shutdownNow();
                if (udpSocket != null && !udpSocket.isClosed())
                    udpSocket.close();
            }));

            // Keep main thread alive
            while (running) {
                Thread.sleep(1000);
            }

            // Cleanup
            channel.close();
            connection.close();

        } catch (Exception e) {
            System.err.println("❌ MaltrailModule fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (scheduler != null)
                scheduler.shutdownNow();
            if (udpListenerExecutor != null)
                udpListenerExecutor.shutdownNow();
            if (udpSocket != null && !udpSocket.isClosed())
                udpSocket.close();
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
        metadata.put("vendor", "Maltrail");
        metadata.put("log_source", "udp://0.0.0.0:" + MALTRAIL_UDP_PORT);
        metadata.put("detection_type", "threat_intel");
        payload.put("metadata", metadata);

        registration.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                registration.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("📝 Sent MaltrailModule registration to ModuleRegistry");
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
        switch (eventType) {
            case "command.maltrail.status":
                System.out.println("📋 Processing status command");
                // Return module status
                break;
            default:
                System.out.println("⚠️  Unknown command type: " + eventType);
        }
    }

    // ---------------------------------------------------------------------
    // Maltrail UDP Listener
    // ---------------------------------------------------------------------

    /**
     * Starts real-time listening for Maltrail's Logstash-style UDP JSON events.
     * Configure Maltrail's sensor with LOGSTASH_SERVER pointing at this host:port.
     */
    private static void startMaltrailUdpListener(Channel channel) {
        udpListenerExecutor = Executors.newSingleThreadExecutor();

        udpListenerExecutor.submit(() -> {
            System.out.println("📡 Starting Maltrail UDP listener on port " + MALTRAIL_UDP_PORT + "...\n");

            try {
                udpSocket = new DatagramSocket(MALTRAIL_UDP_PORT);
                udpSocket.setSoTimeout(1000); // periodic wake to re-check `running`
                byte[] buffer = new byte[65535];

                System.out.println("✅ Maltrail UDP listener bound to port " + MALTRAIL_UDP_PORT);

                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        udpSocket.receive(packet);
                        String jsonPacket = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                        try {
                            parseMaltrailPacket(jsonPacket, channel);
                        } catch (Exception e) {
                            System.err.println("❌ Error parsing Maltrail packet: " + e.getMessage());
                        }
                    } catch (SocketTimeoutException te) {
                        // expected - just loop back to re-check `running`
                    }
                }
            } catch (SocketException se) {
                if (running) {
                    System.err.println("❌ Maltrail UDP socket error: " + se.getMessage());
                }
            } catch (Exception e) {
                System.err.println("❌ Failed to start Maltrail UDP listener: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Parses a single Maltrail UDP JSON datagram and publishes a standardized alert
     * if it carries the required fields. Package-private (not private) so it can be
     * unit tested directly without reflection.
     */
    static void parseMaltrailPacket(String jsonPacket, Channel channel) {
        try {
            // Validate JSON structure before parsing
            if (!jsonPacket.trim().startsWith("{") || !jsonPacket.trim().endsWith("}")) {
                return;
            }

            // Parse the Maltrail UDP event
            MaltrailEvent event = gson.fromJson(jsonPacket, MaltrailEvent.class);

            // Validate essential fields
            if (event.srcIp == null || event.dstIp == null) {
                System.err.println("⚠️  Maltrail event missing required fields - srcIp:"
                        + (event.srcIp != null ? event.srcIp : "NULL")
                        + " dstIp:" + (event.dstIp != null ? event.dstIp : "NULL"));
                return;
            }

            // Create standardized alert message
            publishMaltrailAlert(event, channel);

        } catch (Exception e) {
            System.err.println("❌ Failed to parse Maltrail UDP packet: " + e.getMessage());
        }
    }

    /**
     * Publishes standardized alerts.network.maltrail event
     */
    private static void publishMaltrailAlert(MaltrailEvent event, Channel channel) throws IOException {
        JSONObject alert = buildMaltrailAlertJson(event);

        // Publish the alert to workflow_queue (ModuleRegistry will forward to alerts_queue)
        channel.basicPublish("", WORKFLOW_QUEUE, null,
                alert.toString().getBytes(StandardCharsets.UTF_8));

        JSONObject payload = alert.getJSONObject("payload");
        String logMessage = String.format(
                "📤 Published Maltrail alert: %s [Severity: %s] {%s} %s:%d -> %s:%d (trail: %s)",
                payload.optString("signature"),
                payload.optString("severity"),
                event.proto,
                event.srcIp,
                event.srcPort != null ? event.srcPort : 0,
                event.dstIp,
                event.dstPort != null ? event.dstPort : 0,
                event.trail);
        System.out.println(logMessage);
    }

    /**
     * Builds the standardized alerts.network.maltrail JSON envelope (including payload)
     * without publishing it. Package-private (not private) so it can be unit tested
     * directly - asserting on severity normalization, category derivation, and threat
     * scoring - without needing a live RabbitMQ broker.
     */
    static JSONObject buildMaltrailAlertJson(MaltrailEvent event) {
        JSONObject alert = new JSONObject();
        alert.put("message_type", "alert");
        alert.put("event_id", UUID.randomUUID().toString());

        long alertTimeMillis = event.timestamp != null ? event.timestamp * 1000L : System.currentTimeMillis();
        String timestamp = Instant.ofEpochMilli(alertTimeMillis).toString();
        alert.put("timestamp", timestamp);

        // Add Thesis telemetry metadata (mirrors SuricataModule's telemetry envelope)
        JSONObject telemetry = new JSONObject();
        telemetry.put("alert_generated_time", timestamp);
        telemetry.put("alert_generated_time_ms", alertTimeMillis);

        long systemReceivedTimeMillis = System.currentTimeMillis();
        String systemReceivedTimeStr = Instant.now().toString();
        telemetry.put("system_received_time", systemReceivedTimeStr);
        telemetry.put("system_received_time_ms", systemReceivedTimeMillis);

        double delaySec = (systemReceivedTimeMillis - alertTimeMillis) / 1000.0;
        telemetry.put("alert_to_received_delay_sec", delaySec);
        alert.put("telemetry", telemetry);

        alert.put("event_type", "alerts.network.maltrail");
        alert.put("source_module", MODULE_NAME);

        // Create payload using MaltrailAlertData
        MaltrailAlertData payloadData = new MaltrailAlertData();
        payloadData.setAlertId(generateAlertId(event));
        payloadData.setSignature(event.info != null ? event.info : "unknown");

        payloadData.setSourceIp(event.srcIp);
        payloadData.setDestinationIp(event.dstIp);
        payloadData.setSourcePort(event.srcPort != null ? event.srcPort : 0);
        payloadData.setDestinationPort(event.dstPort != null ? event.dstPort : 0);
        payloadData.setProtocol(event.proto != null ? event.proto : "UNKNOWN");

        String severity = mapSeverity(event.severity);
        payloadData.setSeverity(severity);

        String category = categorizeFromInfo(event.info, event.type);
        payloadData.setCategory(category);
        payloadData.setAlertType(category);

        payloadData.setThreatScore(calculateThreatScore(severity, category));
        payloadData.setConfidenceScore(90); // threat-intel list match; high but not as certain as a live packet-inspection hit

        payloadData.setTrail(event.trail);
        payloadData.setReference(event.reference);
        payloadData.setSensor(event.sensor);

        // Convert to JSON using Gson for proper serialization
        String payloadJson = gson.toJson(payloadData);
        alert.put("payload", new JSONObject(payloadJson));

        return alert;
    }

    // ---------------------------------------------------------------------
    // Utility Methods
    // ---------------------------------------------------------------------

    private static String generateAlertId(MaltrailEvent event) {
        String basis = event.timestamp + "|" + event.srcIp + "|" + event.dstIp + "|" + event.trail;
        return "MALT-" + Integer.toHexString(basis.hashCode());
    }

    /**
     * Normalizes Maltrail's free-text severity to the lowercase critical/high/medium/low
     * scale WorkflowMatcher expects (see WorkflowMatcher.java's severity comparison, which
     * does a case-sensitive equals() on the '==' branch). An absent/unrecognized severity
     * defaults to "high" rather than "low": unlike Suricata's broad pattern matching, every
     * Maltrail event is already a confirmed match against a curated threat-indicator trail.
     */
    private static String mapSeverity(String rawSeverity) {
        if (rawSeverity == null || rawSeverity.isBlank()) {
            return "high";
        }
        String lower = rawSeverity.toLowerCase().trim();
        if (lower.contains("crit")) {
            return "critical";
        } else if (lower.contains("high")) {
            return "high";
        } else if (lower.contains("med")) {
            return "medium";
        } else if (lower.contains("low")) {
            return "low";
        }
        return "high";
    }

    /**
     * Derives category/alert_type from Maltrail's "info" description (preferred, since it
     * carries the actual threat context, e.g. "ransomware", "malware feed: X"), falling back
     * to "maltrail_&lt;type&gt;" (e.g. "maltrail_ip", "maltrail_dns", "maltrail_url") when info
     * doesn't match a known keyword.
     */
    private static String categorizeFromInfo(String info, String type) {
        if (info != null) {
            String lower = info.toLowerCase();
            if (lower.contains("ransomware") || lower.contains("wannacry")
                    || lower.contains("eternalblue") || lower.contains("ms17-010")) {
                return "ransomware";
            } else if (lower.contains("malware") || lower.contains("trojan")) {
                return "malware";
            } else if (lower.contains("c2") || lower.contains("command")) {
                return "c2_communication";
            } else if (lower.contains("phishing")) {
                return "phishing";
            } else if (lower.contains("exploit")) {
                return "exploit";
            } else if (lower.contains("scan") || lower.contains("recon")) {
                return "reconnaissance";
            }
        }
        if (type != null && !type.isBlank()) {
            return "maltrail_" + type.toLowerCase();
        }
        return "maltrail_unknown";
    }

    private static int calculateThreatScore(String severity, String category) {
        int baseScore;
        switch (severity) {
            case "critical":
                baseScore = 85;
                break;
            case "high":
                baseScore = 65;
                break;
            case "medium":
                baseScore = 45;
                break;
            default:
                baseScore = 25;
        }

        if (category.contains("ransomware") || category.contains("c2_communication")) {
            baseScore += 10;
        } else if (category.contains("malware")) {
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

            // Maltrail configuration
            MALTRAIL_UDP_PORT = Integer.parseInt(props.getProperty("maltrail.udp_port", String.valueOf(MALTRAIL_UDP_PORT)));

            // Module identity
            MODULE_ID = props.getProperty("module.id", MODULE_ID);
            MODULE_NAME = props.getProperty("module.name", MODULE_NAME);
            MODULE_TYPE = props.getProperty("module.type", MODULE_TYPE);
            COMMAND_QUEUE = props.getProperty("module.command_queue", COMMAND_QUEUE);
            MODULE_CAPABILITIES = props.getProperty("module.capabilities", MODULE_CAPABILITIES);

            // Heartbeat configuration
            HEARTBEAT_INTERVAL = Integer.parseInt(props.getProperty("module.heartbeat_interval",
                    String.valueOf(HEARTBEAT_INTERVAL)));

            System.out.println("🔧 Loaded MaltrailModule config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("⚠️  Could not load MaltrailModule config (using defaults): " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║  Maltrail User-Defined Module (UDM)    ║");
        System.out.println("║  Threat Intelligence Integration v1.0  ║");
        System.out.println("╚════════════════════════════════════════╝\n");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Module Type: " + MODULE_TYPE);
        System.out.println("Command Queue: " + COMMAND_QUEUE + "\n");
    }

    // ---------------------------------------------------------------------
    // Data structure for parsing Maltrail's Logstash-style UDP JSON events
    // ---------------------------------------------------------------------

    /**
     * Data structure for parsing Maltrail's Logstash-style UDP JSON events, per
     * Maltrail's core/log.py output when LOGSTASH_SERVER is configured.
     */
    private static class MaltrailEvent {
        @SerializedName("timestamp")
        Long timestamp; // unix seconds

        @SerializedName("sensor")
        String sensor;

        @SerializedName("severity")
        String severity;

        @SerializedName("src_ip")
        String srcIp;

        @SerializedName("src_port")
        Integer srcPort;

        @SerializedName("dst_ip")
        String dstIp;

        @SerializedName("dst_port")
        Integer dstPort;

        @SerializedName("proto")
        String proto;

        @SerializedName("type")
        String type; // ip | dns | url

        @SerializedName("trail")
        String trail;

        @SerializedName("info")
        String info;

        @SerializedName("reference")
        String reference;
    }
}
