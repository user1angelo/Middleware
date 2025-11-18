package com.nis1.thesis.udm;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PRTGModule - User-Defined Module for PRTG Integration
 *
 * Standalone Java process that:
 * - Registers itself with the ModuleRegistry via RabbitMQ
 * - Sends periodic heartbeats
 * - Exposes an HTTP endpoint for PRTG "HTTP Action" notifications
 * - Transforms PRTG notifications into standardized alerts.host.prtg events
 *
 * This module follows the JSON message format described in
 * SDK_Detailed_Context.md and PRTG_OpenDaylight_Events.md.
 */
public class PRTGModule {

    // --- Configuration File -------------------------------------------------

    private static final String CONFIG_PATH = "config/prtg-module.properties";

    // --- Module Identity ----------------------------------------------------

    private static String MODULE_ID = "prtg_udm_01";
    private static String MODULE_NAME = "PRTG Monitoring Module";
    private static String MODULE_TYPE = "network_monitoring";
    private static String COMMAND_QUEUE = "prtg_commands_queue";

    // --- RabbitMQ Configuration (align with ModuleRegistry config) ---------

    private static String RABBITMQ_HOST = "192.168.86.76";
    private static int RABBITMQ_PORT = 5672;
    private static String RABBITMQ_USER = "guest";
    private static String RABBITMQ_PASSWORD = "guest";

    private static String WORKFLOW_QUEUE = "workflow_queue"; // UDM → ModuleRegistry

    // --- HTTP Endpoint Configuration ----------------------------------------

    private static String HTTP_HOST = "0.0.0.0";
    private static int HTTP_PORT = 8085;
    private static String HTTP_PATH = "/prtg/alerts";

    // --- Time ---------------------------------------------------------------

    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // --- Runtime state ------------------------------------------------------

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

            System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);

            // 1) Register module with ModuleRegistry
            sendRegistration(channel);

            // 2) Start heartbeats
            startHeartbeats(channel);

            // 3) Start HTTP listener for PRTG
            startHttpServer(channel);

            System.out.println("\n✅ PRTGModule is running. Waiting for PRTG HTTP notifications on " +
                    "http://" + HTTP_HOST + ":" + HTTP_PORT + HTTP_PATH + "\n");

            // Keep main thread alive
            while (running) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println("❌ PRTGModule fatal error: " + e.getMessage());
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

        // Capabilities are used for routing workflow commands
        // (see CommandRoutingListener and registered_modules.capabilities)
        payload.put("capabilities", new org.json.JSONArray()
                .put("prtg_monitoring")
                .put("alert_generation")
                .put("ransomware_detection"));

        payload.put("command_queue", COMMAND_QUEUE);
        payload.put("version", "1.0.0");

        JSONObject metadata = new JSONObject();
        metadata.put("vendor", "PRTG");
        metadata.put("http_endpoint", "http://" + HTTP_HOST + ":" + HTTP_PORT + HTTP_PATH);
        payload.put("metadata", metadata);

        registration.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                registration.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("📝 Sent PRTGModule registration to ModuleRegistry");
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
        // Simple uptime placeholder; can be replaced with real uptime
        payload.put("uptime_seconds", 0);

        heartbeat.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                heartbeat.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("💓 Sent heartbeat");
    }

    // ---------------------------------------------------------------------
    // HTTP Server for PRTG
    // ---------------------------------------------------------------------

    private static void startHttpServer(Channel channel) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        server.createContext(HTTP_PATH, new PRTGHttpHandler(channel));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    /**
     * Simple HTTP handler for PRTG HTTP Action notifications.
     *
     * Expected default payload (application/x-www-form-urlencoded):
     *   ip=<host ip>
     *   host_name=<device name>
     *   sensor_name=<sensor>
     *   sensor_id=<id>
     *   status=<Up/Down/Warning/Error>
     *   message=<PRTG message>
     *   severity=<optional normalized severity>
     *   family=<optional ransomware family, e.g. "Akira">
     */
    private static class PRTGHttpHandler implements HttpHandler {

        private final Channel channel;

        PRTGHttpHandler(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Headers headers = exchange.getRequestHeaders();
            String contentType = headers.getFirst("Content-Type");

            String body = readRequestBody(exchange.getRequestBody());
            Map<String, String> params;

            if (contentType != null && contentType.contains("application/x-www-form-urlencoded")) {
                params = parseFormUrlEncoded(body);
            } else {
                // Fallback: try to parse as key=value pairs; real JSON support can be added later
                params = parseFormUrlEncoded(body);
            }

            try {
                publishPrtgAlert(params, channel);
                sendResponse(exchange, 200, "OK");
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }

        private String readRequestBody(InputStream is) throws IOException {
            byte[] buffer = is.readAllBytes();
            return new String(buffer, StandardCharsets.UTF_8);
        }

        private Map<String, String> parseFormUrlEncoded(String body) {
            Map<String, String> map = new HashMap<>();
            if (body == null || body.isEmpty()) return map;

            String[] pairs = body.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx <= 0) continue;
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, value);
            }
            return map;
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ---------------------------------------------------------------------
    // PRTG → alerts.host.prtg transformation
    // ---------------------------------------------------------------------

    private static void publishPrtgAlert(Map<String, String> params, Channel channel) throws IOException {
        String hostIp = params.getOrDefault("ip", "0.0.0.0");
        String hostName = params.getOrDefault("host_name", "unknown-host");
        String sensorName = params.getOrDefault("sensor_name", "unknown-sensor");
        String sensorId = params.getOrDefault("sensor_id", "");
        String status = params.getOrDefault("status", "Unknown");
        String message = params.getOrDefault("message", "");
        String severity = params.getOrDefault("severity", mapStatusToSeverity(status));
        String family = params.getOrDefault("family", inferFamily(sensorName, message));

        JSONObject alert = new JSONObject();
        alert.put("message_type", "alert");
        alert.put("event_id", UUID.randomUUID().toString());
        alert.put("timestamp", Instant.now().toString());
        alert.put("event_type", "alerts.host.prtg");
        alert.put("source_module", MODULE_NAME);

        JSONObject payload = new JSONObject();
        payload.put("host_ip", hostIp);
        payload.put("host_name", hostName);
        payload.put("mac_address", params.getOrDefault("mac_address", ""));
        payload.put("sensor_name", sensorName);
        payload.put("sensor_id", sensorId);
        payload.put("status", status);
        payload.put("message", message);
        payload.put("severity", severity);
        if (family != null && !family.isEmpty()) {
            payload.put("ransomware_family", family);
        }
        payload.put("prtg_device_id", params.getOrDefault("device_id", ""));
        payload.put("prtg_group", params.getOrDefault("group", ""));

        // Optional: keep raw parameters for debugging
        payload.put("raw", new JSONObject(params));

        alert.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                alert.toString().getBytes(StandardCharsets.UTF_8));

        System.out.println("📤 Published PRTG alert for host " + hostIp +
                " | sensor= " + sensorName + " | status= " + status +
                " | severity= " + severity +
                (family != null ? " | family= " + family : ""));
    }

    private static String mapStatusToSeverity(String status) {
        if (status == null) return "low";
        String s = status.toLowerCase();
        if (s.contains("down") || s.contains("error")) return "critical";
        if (s.contains("warning")) return "medium";
        return "low";
    }

    private static String inferFamily(String sensorName, String message) {
        String combined = ((sensorName != null ? sensorName : "") + " " +
                (message != null ? message : "")).toLowerCase();
        if (combined.contains("akira")) return "Akira";
        if (combined.contains("ransomware")) return "generic";
        return "";
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

            HTTP_HOST = props.getProperty("prtg.http.host", HTTP_HOST);
            HTTP_PORT = Integer.parseInt(props.getProperty("prtg.http.port", String.valueOf(HTTP_PORT)));
            HTTP_PATH = props.getProperty("prtg.http.path", HTTP_PATH);

            MODULE_ID = props.getProperty("module.id", MODULE_ID);
            MODULE_NAME = props.getProperty("module.name", MODULE_NAME);
            MODULE_TYPE = props.getProperty("module.type", MODULE_TYPE);
            COMMAND_QUEUE = props.getProperty("module.command_queue", COMMAND_QUEUE);

            System.out.println("🔧 Loaded PRTGModule config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("⚠️  Could not load PRTGModule config (using defaults): " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  PRTG User-Defined Module (UDM) v1.0    ║");
        System.out.println("╚══════════════════════════════════════════╝\n");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Module Type: " + MODULE_TYPE);
        System.out.println("Command Queue: " + COMMAND_QUEUE + "\n");
    }
}
