package com.nis1.thesis.udm;

import com.rabbitmq.client.*;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OpenDaylightModule - User-Defined Module for SDN Mitigation
 *
 * Standalone Java process that:
 * - Registers itself with the ModuleRegistry via RabbitMQ
 * - Sends periodic heartbeats
 * - Listens on its command queue for workflow_command messages (INITIATE_MITIGATION, SDN_INSTALL_FLOW, etc.)
 * - Translates commands into RESTCONF calls to OpenDaylight (TODO: concrete REST paths/payloads)
 *
 * This module follows the JSON message model described in SDK_Detailed_Context.md
 * and ModuleRegistryLifecycleManager/README.md.
 */
public class OpenDaylightModule {

    // --- Configuration File -------------------------------------------------

    private static final String CONFIG_PATH = "config/opendaylight-module.properties";

    // --- Module Identity ----------------------------------------------------

    private static String MODULE_ID = "odl_sdn_01";
    private static String MODULE_NAME = "OpenDaylight SDN Module";
    private static String MODULE_TYPE = "sdn_controller";
    private static String COMMAND_QUEUE = "odl_commands_queue";

    // Capabilities used for routing by CommandRoutingListener
    private static final String CAP_INITIATE_MITIGATION = "INITIATE_MITIGATION";
    private static final String CAP_SDN_INSTALL_FLOW = "SDN_INSTALL_FLOW";

    // --- RabbitMQ Configuration --------------------------------------------

    private static String RABBITMQ_HOST = "192.168.86.76";
    private static int RABBITMQ_PORT = 5672;
    private static String RABBITMQ_USER = "guest";
    private static String RABBITMQ_PASSWORD = "guest";

    private static String WORKFLOW_QUEUE = "workflow_queue";

    // --- OpenDaylight RESTCONF (placeholder) --------------------------------

    private static String ODL_BASE_URL = "http://opendaylight:8181";
    private static String ODL_USERNAME = "admin";
    private static String ODL_PASSWORD = "admin";

    // --- Time ---------------------------------------------------------------

    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // --- Runtime ------------------------------------------------------------

    private static ScheduledExecutorService scheduler;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        printBanner();

        // Load configuration from properties file (if present)
        loadConfig();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            channel.queueDeclare(WORKFLOW_QUEUE, true, false, false, null);
            channel.queueDeclare(COMMAND_QUEUE, true, false, false, null);

            System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);

            // 1) Register module
            sendRegistration(channel);

            // 2) Start heartbeats
            startHeartbeats(channel);

            // 3) Start command listener
            startCommandListener(channel);

            System.out.println("\n✅ OpenDaylightModule is running. Waiting for workflow_command messages on " + COMMAND_QUEUE + "\n");

            // Keep alive
            while (running) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println("❌ OpenDaylightModule fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Registration & Heartbeat
    // ---------------------------------------------------------------------

    private static void sendRegistration(Channel channel) throws IOException {
        JSONObject registration = new JSONObject();
        registration.put("message_type", "registration");
        registration.put("event_id", "reg-" + UUID.randomUUID());
        registration.put("timestamp", getCurrentManilaTime());
        registration.put("event_type", "system.module.registration");
        registration.put("source_module", MODULE_NAME);

        JSONObject payload = new JSONObject();
        payload.put("module_id", MODULE_ID);
        payload.put("module_name", MODULE_NAME);
        payload.put("module_type", MODULE_TYPE);

        org.json.JSONArray capabilities = new org.json.JSONArray();
        capabilities.put(CAP_INITIATE_MITIGATION);
        capabilities.put(CAP_SDN_INSTALL_FLOW);
        capabilities.put("sdn_block_ip");
        capabilities.put("sdn_block_mac");
        payload.put("capabilities", capabilities);

        payload.put("command_queue", COMMAND_QUEUE);
        payload.put("version", "1.0.0");

        JSONObject metadata = new JSONObject();
        metadata.put("controller", "OpenDaylight");
        metadata.put("base_url", ODL_BASE_URL);
        payload.put("metadata", metadata);

        registration.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                registration.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("📝 Sent OpenDaylightModule registration to ModuleRegistry");
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

    private static void sendHeartbeat(Channel channel) throws IOException {
        JSONObject heartbeat = new JSONObject();
        heartbeat.put("message_type", "heartbeat");
        heartbeat.put("event_id", "hb-" + UUID.randomUUID());
        heartbeat.put("timestamp", getCurrentManilaTime());
        heartbeat.put("event_type", "system.module.heartbeat");
        heartbeat.put("source_module", MODULE_NAME);

        JSONObject payload = new JSONObject();
        payload.put("module_id", MODULE_ID);
        payload.put("status", "online");
        payload.put("uptime_seconds", 0);

        heartbeat.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                heartbeat.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("💓 Sent heartbeat");
    }

    // ---------------------------------------------------------------------
    // Command Listener
    // ---------------------------------------------------------------------

    private static void startCommandListener(Channel channel) throws IOException {
        System.out.println("🎯 Command listener starting on: " + COMMAND_QUEUE);

        DeliverCallback callback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                handleCommandMessage(message, channel);
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            } catch (Exception e) {
                System.err.println("❌ Failed to handle command: " + e.getMessage());
                e.printStackTrace();
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            }
        };

        channel.basicConsume(COMMAND_QUEUE, false, callback, consumerTag -> {});
    }

    private static void handleCommandMessage(String messageJson, Channel channel) {
        JSONObject json = new JSONObject(messageJson);
        String messageType = json.optString("message_type", "unknown");
        if (!"workflow_command".equals(messageType)) {
            System.out.println("⚠️  Ignoring non-command message type: " + messageType);
            return;
        }

        JSONObject payload = json.getJSONObject("payload");
        String command = payload.getString("command");
        JSONObject parameters = payload.optJSONObject("parameters");
        if (parameters == null) parameters = new JSONObject();

        System.out.println("🎯 Received workflow command: " + command);

        switch (command) {
            case "INITIATE_MITIGATION":
                handleInitiateMitigation(parameters, channel, json);
                break;
            case "SDN_INSTALL_FLOW":
                handleSdnInstallFlow(parameters, channel, json);
                break;
            default:
                System.out.println("⚠️  Unsupported command for OpenDaylightModule: " + command);
        }
    }

    private static void handleInitiateMitigation(JSONObject params, Channel channel, JSONObject original) {
        String targetHost = params.optString("targetHost", "");
        String action = params.optString("action", "ISOLATE");
        String family = params.optString("family", "");

        System.out.println("🔐 INITIATE_MITIGATION for host=" + targetHost + " action=" + action + " family=" + family);

        boolean success = false;
        String details;
        try {
            if ("BLOCK_IP".equalsIgnoreCase(action) || "ISOLATE".equalsIgnoreCase(action) || "QUARANTINE".equalsIgnoreCase(action)) {
                // Baseline: IP-based isolation
                performBlockIp(targetHost);
                success = true;
                details = "Applied IP-based isolation via OpenDaylight (placeholder RESTCONF).";
            } else if ("BLOCK_MAC".equalsIgnoreCase(action)) {
                String mac = params.optString("targetMac", "");
                performBlockMac(mac);
                success = true;
                details = "Applied MAC-based isolation via OpenDaylight (placeholder RESTCONF).";
            } else {
                details = "Unsupported action: " + action;
            }
        } catch (Exception e) {
            success = false;
            details = "Error during mitigation: " + e.getMessage();
        }

        publishAuditLogForMitigation(targetHost, action, family, success, details, channel, original);
    }

    private static void handleSdnInstallFlow(JSONObject params, Channel channel, JSONObject original) {
        String switchId = params.optString("switchId", "");
        String flowId = params.optString("flowId", "");
        int priority = params.optInt("priority", 32768);

        System.out.println("🧩 SDN_INSTALL_FLOW switch=" + switchId + " flowId=" + flowId + " priority=" + priority);

        // TODO: translate params into a real RESTCONF flow-install call
        boolean success = false;
        String details;
        try {
            performInstallFlowViaOdl(params);
            success = true;
            details = "Installed SDN flow via OpenDaylight (placeholder RESTCONF).";
        } catch (Exception e) {
            success = false;
            details = "Error installing SDN flow: " + e.getMessage();
        }

        publishAuditLogForFlow(switchId, flowId, success, details, channel, original);
    }

    // ---------------------------------------------------------------------
    // OpenDaylight RESTCONF placeholders
    // ---------------------------------------------------------------------

    private static void performBlockIp(String ip) throws IOException {
        if (ip == null || ip.isEmpty()) {
            System.out.println("⚠️  No target IP provided for BLOCK_IP");
            return;
        }
        System.out.println("[ODL] (TODO) Blocking IP via RESTCONF: " + ip);
        // Example placeholder call; replace with real ODL RESTCONF flow programming
        // sendOdlRequest("/restconf/operations/example:block-ip", "{...json body...}");
    }

    private static void performBlockMac(String mac) throws IOException {
        if (mac == null || mac.isEmpty()) {
            System.out.println("⚠️  No target MAC provided for BLOCK_MAC");
            return;
        }
        System.out.println("[ODL] (TODO) Blocking MAC via RESTCONF: " + mac);
        // Example placeholder call; replace with real ODL RESTCONF flow programming
        // sendOdlRequest("/restconf/operations/example:block-mac", "{...json body...}");
    }

    private static void performInstallFlowViaOdl(JSONObject params) throws IOException {
        System.out.println("[ODL] (TODO) Installing flow via RESTCONF with params: " + params.toString());
        // Example placeholder; in a real system, map params → ODL flow JSON and PUT to /restconf/config/...
        // sendOdlRequest("/restconf/config/example:flow", params.toString());
    }

    @SuppressWarnings("unused")
    private static void sendOdlRequest(String path, String body) throws IOException {
        String urlStr = ODL_BASE_URL + path;
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");

        String basicAuth = Base64.getEncoder().encodeToString((ODL_USERNAME + ":" + ODL_PASSWORD).getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + basicAuth);

        conn.setDoOutput(true);
        if (body != null) {
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        System.out.println("[ODL] RESTCONF call " + urlStr + " returned HTTP " + code);
        conn.disconnect();
    }

    // ---------------------------------------------------------------------
    // AUDIT_LOG helpers
    // ---------------------------------------------------------------------

    private static void publishAuditLogForMitigation(String hostIp,
                                                     String action,
                                                     String family,
                                                     boolean success,
                                                     String details,
                                                     Channel channel,
                                                     JSONObject original) {
        try {
            JSONObject event = new JSONObject();
            event.put("message_type", "alert");
            event.put("event_id", UUID.randomUUID().toString());
            event.put("timestamp", getCurrentManilaTime());
            event.put("event_type", "AUDIT_LOG");
            event.put("source_module", MODULE_NAME);

            JSONObject payload = new JSONObject();
            payload.put("action", success ? "HOST_ISOLATED" : "HOST_ISOLATION_FAILED");
            payload.put("source", "OpenDaylight");
            payload.put("severity", success ? "info" : "error");
            payload.put("host_ip", hostIp);
            if (family != null && !family.isEmpty()) {
                payload.put("family", family);
            }
            payload.put("message", details);
            payload.put("related_event_id", original.optString("event_id", ""));

            event.put("payload", payload);

            channel.basicPublish("", WORKFLOW_QUEUE, null,
                    event.toString().getBytes(StandardCharsets.UTF_8));

            System.out.println("🧾 AUDIT_LOG (mitigation): " + details);
        } catch (Exception e) {
            System.err.println("❌ Failed to publish AUDIT_LOG (mitigation): " + e.getMessage());
        }
    }

    private static void publishAuditLogForFlow(String switchId,
                                               String flowId,
                                               boolean success,
                                               String details,
                                               Channel channel,
                                               JSONObject original) {
        try {
            JSONObject event = new JSONObject();
            event.put("message_type", "alert");
            event.put("event_id", UUID.randomUUID().toString());
            event.put("timestamp", getCurrentManilaTime());
            event.put("event_type", "AUDIT_LOG");
            event.put("source_module", MODULE_NAME);

            JSONObject payload = new JSONObject();
            payload.put("action", success ? "SDN_FLOW_INSTALLED" : "SDN_FLOW_INSTALL_FAILED");
            payload.put("source", "OpenDaylight");
            payload.put("severity", success ? "info" : "error");
            payload.put("message", details);
            payload.put("switchId", switchId);
            payload.put("flowId", flowId);
            payload.put("related_event_id", original.optString("event_id", ""));

            event.put("payload", payload);

            channel.basicPublish("", WORKFLOW_QUEUE, null,
                    event.toString().getBytes(StandardCharsets.UTF_8));

            System.out.println("🧾 AUDIT_LOG (flow): " + details);
        } catch (Exception e) {
            System.err.println("❌ Failed to publish AUDIT_LOG (flow): " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------

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
            COMMAND_QUEUE = props.getProperty("rabbitmq.command_queue", COMMAND_QUEUE);

            ODL_BASE_URL = props.getProperty("odl.base_url", ODL_BASE_URL);
            ODL_USERNAME = props.getProperty("odl.username", ODL_USERNAME);
            ODL_PASSWORD = props.getProperty("odl.password", ODL_PASSWORD);

            MODULE_ID = props.getProperty("module.id", MODULE_ID);
            MODULE_NAME = props.getProperty("module.name", MODULE_NAME);
            MODULE_TYPE = props.getProperty("module.type", MODULE_TYPE);

            System.out.println("🔧 Loaded OpenDaylightModule config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("⚠️  Could not load OpenDaylightModule config (using defaults): " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║  OpenDaylight User-Defined Module v1.0    ║");
        System.out.println("╚════════════════════════════════════════════╝\n");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Module Type: " + MODULE_TYPE);
        System.out.println("Command Queue: " + COMMAND_QUEUE + "\n");
    }
}
