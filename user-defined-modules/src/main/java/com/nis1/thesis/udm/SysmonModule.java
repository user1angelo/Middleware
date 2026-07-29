package com.nis1.thesis.udm;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.nis1.thesis.sdk.AlertEnvelopeBuilder;

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
 * SysmonModule - User-Defined Module for Windows Sysmon Host-Telemetry Integration
 *
 * Standalone Java process that:
 * - Registers itself with the ModuleRegistry via RabbitMQ
 * - Sends periodic heartbeats to maintain online status
 * - Listens for Sysmon events shipped as JSON over UDP, the same way real deployments
 *   integrate Sysmon into a Linux-based SOAR/SIEM: a shipper (Winlogbeat/NXLog) forwards
 *   Windows Event Log entries as JSON over the network, since raw Windows Event Log access
 *   isn't available/practical from a Linux-hosted module
 * - Transforms Sysmon Process Create (event ID 1) and File Create (event ID 11) events into
 *   standardized alerts.host.sysmon events, with ransomware pre-encryption command detection
 * - Listens for orchestration commands from the workflow engine
 *
 * Mirrors MaltrailModule's UDP-listener shape - zero changes to CoreSystemApi,
 * WorkflowMatcher, WorkflowEngine, or OpenDaylightModule were required to add this module.
 */
public class SysmonModule {

    // --- Configuration File -------------------------------------------------

    private static final String CONFIG_PATH = "config/sysmon-module.properties";

    // --- Module Identity (loaded from config) -------------------------------

    private static String MODULE_ID = "sysmon-module";
    private static String MODULE_NAME = "Sysmon UDM";
    private static String MODULE_TYPE = "host_security";
    private static String COMMAND_QUEUE = "sysmon-module_commands_queue";
    private static String MODULE_CAPABILITIES = "host_telemetry,process_monitoring,alert_generation";

    // --- RabbitMQ Configuration (align with ModuleRegistry) -----------------

    private static String RABBITMQ_HOST = "localhost";
    private static int RABBITMQ_PORT = 5672;
    private static String RABBITMQ_USER = "user";
    private static String RABBITMQ_PASSWORD = "password";
    private static String WORKFLOW_QUEUE = "workflow_queue"; // UDM -> ModuleRegistry

    // --- Sysmon Configuration ----------------------------------------------

    private static int SYSMON_UDP_PORT = 8482;

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

        loadConfig();

        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(RABBITMQ_HOST);
            factory.setPort(RABBITMQ_PORT);
            factory.setUsername(RABBITMQ_USER);
            factory.setPassword(RABBITMQ_PASSWORD);

            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();

            channel.queueDeclare(WORKFLOW_QUEUE, true, false, false, null);
            channel.queueDeclare(COMMAND_QUEUE, true, false, false, null);

            System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);

            sendRegistration(channel);
            Thread.sleep(1000);

            startHeartbeats(channel);
            startCommandListener(channel);
            startSysmonUdpListener(channel);

            System.out.println("\n✅ SysmonModule is running. Press Ctrl+C to stop.\n");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Shutting down SysmonModule...");
                running = false;
                if (scheduler != null)
                    scheduler.shutdownNow();
                if (udpListenerExecutor != null)
                    udpListenerExecutor.shutdownNow();
                if (udpSocket != null && !udpSocket.isClosed())
                    udpSocket.close();
            }));

            while (running) {
                Thread.sleep(1000);
            }

            channel.close();
            connection.close();

        } catch (Exception e) {
            System.err.println("❌ SysmonModule fatal error: " + e.getMessage());
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

        JSONArray capabilities = new JSONArray();
        for (String cap : MODULE_CAPABILITIES.split(",")) {
            capabilities.put(cap.trim());
        }
        payload.put("capabilities", capabilities);

        payload.put("command_queue", COMMAND_QUEUE);
        payload.put("version", "1.0.0");

        JSONObject metadata = new JSONObject();
        metadata.put("vendor", "Sysmon");
        metadata.put("log_source", "udp://0.0.0.0:" + SYSMON_UDP_PORT);
        metadata.put("detection_type", "host_telemetry");
        payload.put("metadata", metadata);

        registration.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                registration.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("📝 Sent SysmonModule registration to ModuleRegistry");
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
            case "command.sysmon.status":
                System.out.println("📋 Processing status command");
                break;
            default:
                System.out.println("⚠️  Unknown command type: " + eventType);
        }
    }

    // ---------------------------------------------------------------------
    // Sysmon UDP Listener
    // ---------------------------------------------------------------------

    /**
     * Starts real-time listening for Sysmon events shipped as JSON over UDP (e.g. by a
     * Winlogbeat/NXLog-style forwarder configured to point at this host:port).
     */
    private static void startSysmonUdpListener(Channel channel) {
        udpListenerExecutor = Executors.newSingleThreadExecutor();

        udpListenerExecutor.submit(() -> {
            System.out.println("📡 Starting Sysmon UDP listener on port " + SYSMON_UDP_PORT + "...\n");

            try {
                udpSocket = new DatagramSocket(SYSMON_UDP_PORT);
                udpSocket.setSoTimeout(1000);
                byte[] buffer = new byte[65535];

                System.out.println("✅ Sysmon UDP listener bound to port " + SYSMON_UDP_PORT);

                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        udpSocket.receive(packet);
                        String jsonPacket = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                        try {
                            parseSysmonPacket(jsonPacket, channel);
                        } catch (Exception e) {
                            System.err.println("❌ Error parsing Sysmon packet: " + e.getMessage());
                        }
                    } catch (SocketTimeoutException te) {
                        // expected - just loop back to re-check `running`
                    }
                }
            } catch (SocketException se) {
                if (running) {
                    System.err.println("❌ Sysmon UDP socket error: " + se.getMessage());
                }
            } catch (Exception e) {
                System.err.println("❌ Failed to start Sysmon UDP listener: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Parses a single Sysmon UDP JSON datagram and publishes a standardized alert if it
     * carries the required fields and is an event ID this module handles. Package-private
     * (not private) so it can be unit tested directly without reflection.
     */
    static void parseSysmonPacket(String jsonPacket, Channel channel) {
        try {
            if (!jsonPacket.trim().startsWith("{") || !jsonPacket.trim().endsWith("}")) {
                return;
            }

            SysmonEvent event = gson.fromJson(jsonPacket, SysmonEvent.class);

            if (event.eventId == null || event.computer == null) {
                System.err.println("⚠️  Sysmon event missing required fields - eventId:"
                        + (event.eventId != null ? event.eventId : "NULL")
                        + " computer:" + (event.computer != null ? event.computer : "NULL"));
                return;
            }

            // Only Process Create (1) and File Create (11) are handled - other event IDs are
            // silently ignored, mirroring SuricataModule's non-alert event_type skip. A real
            // shipper would typically already be configured to only forward these two.
            if (event.eventId != 1 && event.eventId != 11) {
                return;
            }

            publishSysmonAlert(event, channel);

        } catch (Exception e) {
            System.err.println("❌ Failed to parse Sysmon UDP packet: " + e.getMessage());
        }
    }

    /**
     * Publishes standardized alerts.host.sysmon event
     */
    private static void publishSysmonAlert(SysmonEvent event, Channel channel) throws IOException {
        JSONObject alert = buildSysmonAlertJson(event);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                alert.toString().getBytes(StandardCharsets.UTF_8));

        JSONObject payload = alert.getJSONObject("payload");
        String logMessage = String.format(
                "📤 Published Sysmon alert: %s [Severity: %s] computer=%s eventId=%d",
                payload.optString("signature"), payload.optString("severity"),
                event.computer, event.eventId);
        System.out.println(logMessage);
    }

    /**
     * Builds the standardized alerts.host.sysmon JSON envelope (including payload) without
     * publishing it. Package-private (not private) so it can be unit tested directly -
     * asserting on severity/category/threat scoring - without needing a live RabbitMQ broker.
     */
    static JSONObject buildSysmonAlertJson(SysmonEvent event) {
        long alertTimeMillis = event.timestamp != null ? event.timestamp * 1000L : System.currentTimeMillis();
        String timestamp = Instant.ofEpochMilli(alertTimeMillis).toString();

        JSONObject telemetry = new JSONObject();
        telemetry.put("alert_generated_time", timestamp);
        telemetry.put("alert_generated_time_ms", alertTimeMillis);

        long systemReceivedTimeMillis = System.currentTimeMillis();
        String systemReceivedTimeStr = Instant.now().toString();
        telemetry.put("system_received_time", systemReceivedTimeStr);
        telemetry.put("system_received_time_ms", systemReceivedTimeMillis);

        double delaySec = (systemReceivedTimeMillis - alertTimeMillis) / 1000.0;
        telemetry.put("alert_to_received_delay_sec", delaySec);

        SysmonAlertData payloadData = new SysmonAlertData();
        payloadData.setAlertId(generateAlertId(event));

        boolean isRansomwareIndicator = isRansomwareCommand(event.commandLine);
        String category = categorize(event.eventId, isRansomwareIndicator);
        String severity = isRansomwareIndicator ? "critical" : "medium";

        payloadData.setSignature(describeEvent(event, isRansomwareIndicator));
        payloadData.setSeverity(severity);
        payloadData.setCategory(category);
        payloadData.setAlertType(category);
        payloadData.setThreatScore(isRansomwareIndicator ? 90 : 30);
        payloadData.setConfidenceScore(isRansomwareIndicator ? 95 : 40);
        payloadData.setSysmonEventId(event.eventId);
        payloadData.setComputer(event.computer);
        payloadData.setImage(event.image);
        payloadData.setCommandLine(event.commandLine);
        payloadData.setUser(event.user);

        String payloadJson = gson.toJson(payloadData);

        return AlertEnvelopeBuilder.create()
                .timestamp(Instant.parse(timestamp))
                .telemetry(telemetry)
                .eventType("alerts.host.sysmon")
                .sourceModule(MODULE_NAME)
                .payload(new JSONObject(payloadJson))
                .build();
    }

    // ---------------------------------------------------------------------
    // Utility Methods
    // ---------------------------------------------------------------------

    private static String generateAlertId(SysmonEvent event) {
        String basis = event.timestamp + "|" + event.computer + "|" + event.image + "|" + event.commandLine;
        return "SYSMON-" + Integer.toHexString(basis.hashCode());
    }

    /**
     * Flags known Windows pre-encryption/ransomware-preparation commands: shadow copy
     * deletion, backup catalog deletion, boot-recovery-disabling, and forced cipher wipes -
     * the same class of indicators the repo's existing Dharma/Petya/Ryuk workflows target.
     */
    private static boolean isRansomwareCommand(String commandLine) {
        if (commandLine == null) {
            return false;
        }
        String lower = commandLine.toLowerCase();
        return (lower.contains("vssadmin") && lower.contains("delete") && lower.contains("shadow"))
                || (lower.contains("wbadmin") && lower.contains("delete"))
                || (lower.contains("bcdedit") && lower.contains("bootstatuspolicy"))
                || (lower.contains("cipher") && lower.contains("/w"))
                || (lower.contains("wmic") && lower.contains("shadowcopy") && lower.contains("delete"));
    }

    private static String categorize(int sysmonEventId, boolean isRansomwareIndicator) {
        if (isRansomwareIndicator) {
            return "ransomware";
        }
        return sysmonEventId == 11 ? "file_creation" : "process_creation";
    }

    private static String describeEvent(SysmonEvent event, boolean isRansomwareIndicator) {
        if (isRansomwareIndicator) {
            return "Ransomware pre-encryption command detected: " + event.commandLine;
        }
        if (event.eventId == 11) {
            return "Sysmon file create: " + event.targetFilename;
        }
        return "Sysmon process create: " + event.image;
    }

    private static String getCurrentManilaTime() {
        ZonedDateTime manilaTime = ZonedDateTime.now(MANILA_ZONE);
        return manilaTime.format(ISO_FORMATTER);
    }

    private static void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);

            RABBITMQ_HOST = props.getProperty("rabbitmq.host", RABBITMQ_HOST);
            RABBITMQ_PORT = Integer.parseInt(props.getProperty("rabbitmq.port", String.valueOf(RABBITMQ_PORT)));
            RABBITMQ_USER = props.getProperty("rabbitmq.user", RABBITMQ_USER);
            RABBITMQ_PASSWORD = props.getProperty("rabbitmq.password", RABBITMQ_PASSWORD);
            WORKFLOW_QUEUE = props.getProperty("rabbitmq.workflow_queue", WORKFLOW_QUEUE);

            SYSMON_UDP_PORT = Integer.parseInt(props.getProperty("sysmon.udp_port", String.valueOf(SYSMON_UDP_PORT)));

            MODULE_ID = props.getProperty("module.id", MODULE_ID);
            MODULE_NAME = props.getProperty("module.name", MODULE_NAME);
            MODULE_TYPE = props.getProperty("module.type", MODULE_TYPE);
            COMMAND_QUEUE = props.getProperty("module.command_queue", COMMAND_QUEUE);
            MODULE_CAPABILITIES = props.getProperty("module.capabilities", MODULE_CAPABILITIES);

            HEARTBEAT_INTERVAL = Integer.parseInt(props.getProperty("module.heartbeat_interval",
                    String.valueOf(HEARTBEAT_INTERVAL)));

            System.out.println("🔧 Loaded SysmonModule config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("⚠️  Could not load SysmonModule config (using defaults): " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║  Sysmon User-Defined Module (UDM)      ║");
        System.out.println("║  Host Telemetry Integration v1.0       ║");
        System.out.println("╚════════════════════════════════════════╝\n");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Module Type: " + MODULE_TYPE);
        System.out.println("Command Queue: " + COMMAND_QUEUE + "\n");
    }

    // ---------------------------------------------------------------------
    // Data structure for parsing Sysmon events shipped as JSON (e.g. via Winlogbeat/NXLog)
    // ---------------------------------------------------------------------

    private static class SysmonEvent {
        @SerializedName("event_id")
        Integer eventId; // 1 = Process Create, 11 = File Create

        @SerializedName("computer")
        String computer;

        @SerializedName("timestamp")
        Long timestamp; // unix seconds

        @SerializedName("image")
        String image;

        @SerializedName("command_line")
        String commandLine;

        @SerializedName("parent_image")
        String parentImage;

        @SerializedName("user")
        String user;

        @SerializedName("target_filename")
        String targetFilename; // populated for event ID 11 (File Create)
    }
}
