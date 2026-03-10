package com.nis1.thesis.udm;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.ModuleHelper;
import com.nis1.thesis.sdk.PluggableModule;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ZeekHttpModule - SDK-based pluggable module that exposes an HTTP endpoint
 * for Zeek JSON notices and publishes standardized alerts.network.zeek events
 * via the SOAR SDK.
 *
 * This module is intended to be loaded by the Module Registry & Lifecycle
 * Manager, similar to SuricataHttpModule and OpenDaylightModule. It does not
 * connect to RabbitMQ directly; all messaging goes through CoreSystemApi.
 */
public class ZeekHttpModule implements PluggableModule {

    private static final String CONFIG_PATH = "config/zeek-http-module.properties";

    private CoreSystemApi api;
    private ModuleHelper helper;
    private HttpServer httpServer;
    private ExecutorService httpExecutor;

    // Module identity (override via config)
    private String moduleId = "zeek_http_01";
    private String moduleName = "Zeek HTTP NSM Module";
    @SuppressWarnings("FieldCanBeLocal")
    private String moduleType = "network_security";

    // HTTP listener configuration (override via config)
    private String httpHost = "0.0.0.0";
    private int httpPort = 8091;
    private String httpPath = "/zeek/notices";

    // Alert processing configuration
    private String minSeverity = "medium"; // low, medium, high, critical
    private boolean enableAllNotices = false; // If true, process all notices regardless of type

    private volatile boolean running = false;
    private final Gson gson = new Gson();

    @Override
    public String getName() {
        return moduleName;
    }

    @Override
    public void initialize(CoreSystemApi api) {
        this.api = api;
        this.helper = new ModuleHelper(api);

        loadConfig();

        helper.log(getName(), "INFO", "Initializing ZeekHttpModule (id=" + moduleId + ")");
        helper.log(getName(), "INFO",
                "HTTP listener configured at host=" + httpHost + ", port=" + httpPort + ", path=" + httpPath);

        startHttpServer();
        running = true;

        helper.log(getName(), "INFO", "ZeekHttpModule initialized and ready to receive notices");
    }

    @Override
    public void shutdown() {
        running = false;

        if (httpServer != null) {
            httpServer.stop(1);
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
        if (helper != null) {
            helper.log(getName(), "INFO", "ZeekHttpModule shut down");
        }
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
            httpServer.createContext(httpPath, new ZeekHttpHandler());

            httpExecutor = Executors.newCachedThreadPool();
            httpServer.setExecutor(httpExecutor);
            httpServer.start();

            helper.log(getName(), "INFO",
                    "Started HTTP server on port " + httpPort + " for path " + httpPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Zeek HTTP server on port " + httpPort, e);
        }
    }

    /**
     * Load module configuration from properties file.
     */
    private void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);

            moduleId = props.getProperty("module.id", moduleId);
            moduleName = props.getProperty("module.name", moduleName);
            moduleType = props.getProperty("module.type", moduleType);

            httpHost = props.getProperty("zeek.http.host", httpHost);
            httpPort = Integer.parseInt(props.getProperty("zeek.http.port", String.valueOf(httpPort)));
            httpPath = props.getProperty("zeek.http.path", httpPath);

            minSeverity = props.getProperty("zeek.min_severity", minSeverity);
            enableAllNotices = Boolean.parseBoolean(props.getProperty("zeek.enable_all_notices", "false"));

            System.out.println("[ZeekHttpModule] Loaded config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("[ZeekHttpModule][WARN] Could not load config (using defaults): " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // HTTP Handler
    // ---------------------------------------------------------------------

    private class ZeekHttpHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Headers headers = exchange.getRequestHeaders();
            String contentType = headers.getFirst("Content-Type");

            if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
                helper.log(getName(), "WARN",
                        "Received Zeek HTTP request with non-JSON Content-Type=" + contentType);
            }

            String body = readRequestBody(exchange.getRequestBody());

            try {
                handleZeekJson(body);
                sendResponse(exchange, 200, "OK");
            } catch (Exception e) {
                helper.log(getName(), "ERROR", "Failed to handle Zeek HTTP notice: " + e.getMessage());
                sendResponse(exchange, 500, "Internal Server Error");
            }
        }

        private String readRequestBody(InputStream is) throws IOException {
            byte[] buffer = is.readAllBytes();
            return new String(buffer, StandardCharsets.UTF_8);
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
    // Zeek JSON handling
    // ---------------------------------------------------------------------

    /**
     * Handle a single Zeek JSON notice received over HTTP.
     */
    private void handleZeekJson(String jsonBody) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            helper.log(getName(), "WARN", "Received empty Zeek JSON body");
            return;
        }

        ZeekNotice notice = gson.fromJson(jsonBody, ZeekNotice.class);
        if (notice == null) {
            helper.log(getName(), "WARN", "Failed to deserialize Zeek JSON body");
            return;
        }

        // Validate required fields
        if (notice.note == null || notice.src == null) {
            helper.log(getName(), "WARN", "Zeek notice missing required fields (note or src); skipping");
            return;
        }

        // Filter notices based on configuration
        if (!enableAllNotices && !isRelevantNotice(notice.note, notice.msg)) {
            helper.log(getName(), "DEBUG", "Ignoring non-relevant Zeek notice: " + notice.note);
            return;
        }

        publishZeekAlert(notice);
    }

    /**
     * Check if the notice is relevant for security monitoring
     */
    private boolean isRelevantNotice(String noteType, String message) {
        if (noteType == null)
            return false;

        String combined = (noteType + " " + (message != null ? message : "")).toLowerCase();

        // Match security-relevant patterns
        return combined.contains("ransomware") ||
                combined.contains("malware") ||
                combined.contains("scan::port_scan") ||
                combined.contains("scan::address_scan") ||
                combined.contains("intel::notice") ||
                combined.contains("c2") ||
                combined.contains("command and control") ||
                combined.contains("lateral") ||
                combined.contains("smb") ||
                combined.contains("eternalblue") ||
                combined.contains("exploit") ||
                combined.contains("crypto") ||
                combined.contains("smb_mapping_event");
    }

    /**
     * Convert Zeek notice into standardized ZeekAlertData and publish it as an SDK
     * Event.
     */
    private void publishZeekAlert(ZeekNotice notice) {
        ZeekAlertData payload = new ZeekAlertData();

        // Identification
        payload.setAlertId(generateAlertId());
        payload.setNoteType(notice.note);
        payload.setSignature(notice.msg != null ? notice.msg : notice.note);

        String subMessage = notice.sub != null ? notice.sub : "";
        if (notice.path != null && !notice.path.isEmpty()) {
            System.out.println("[ZeekHttpModule][DEBUG] Received SMB mapping path: " + notice.path);
            subMessage = subMessage.isEmpty() ? "Path: " + notice.path : subMessage + " | Path: " + notice.path;
        }
        payload.setSubMessage(subMessage);

        // Determine severity
        String severity = determineSeverity(notice.note, notice.msg, notice.path);
        payload.setSeverity(severity);

        // Network context
        payload.setSourceIp(notice.src);
        payload.setDestinationIp(notice.dst);
        if (notice.p != null && notice.p > 0) {
            payload.setSourcePort(notice.p);
        }
        if (notice.dstPort != null && notice.dstPort > 0) {
            payload.setDestinationPort(notice.dstPort);
        }
        payload.setProtocol(notice.proto != null ? notice.proto.toUpperCase() : "UNKNOWN");

        // Classification
        String category = categorizeFromNote(notice.note, notice.msg, notice.path);
        payload.setCategory(category);
        payload.setAlertType(category);

        // Threat scores
        payload.setThreatScore(calculateThreatScore(severity, category));
        payload.setConfidenceScore(90); // Zeek is behavioral, slightly lower than signature-based

        // Optional metadata
        if (notice.uid != null) {
            payload.setUid(notice.uid);
        }
        if (notice.actions != null) {
            payload.setActions(notice.actions);
        }

        Event<ZeekAlertData> event = Event.of("alerts.network.zeek", payload);
        api.publishEvent(event);

        helper.log(getName(), "INFO",
                String.format(
                        "Published Zeek alert: %s [severity=%s, src=%s, dst=%s]",
                        payload.getNoteType(),
                        payload.getSeverity(),
                        payload.getSourceIp(),
                        payload.getDestinationIp() != null ? payload.getDestinationIp() : "N/A"));
    }

    // ---------------------------------------------------------------------
    // Utility methods
    // ---------------------------------------------------------------------

    private String determineSeverity(String noteType, String message, String path) {
        String combined = (noteType + " " + (message != null ? message : "")).toLowerCase();

        if (combined.contains("ransomware") ||
                combined.contains("malware") ||
                combined.contains("eternalblue") ||
                combined.contains("wannacry") ||
                combined.contains("smb_mapping_event") ||
                combined.contains("c2")) {
            return "critical";
        } else if (combined.contains("scan::port_scan") ||
                combined.contains("intel::notice") ||
                combined.contains("lateral") ||
                combined.contains("exploit")) {
            return "high";
        } else if (combined.contains("scan::address_scan") ||
                combined.contains("smb")) {
            return "medium";
        }
        return "low";
    }

    private String categorizeFromNote(String noteType, String message, String path) {
        String combined = (noteType + " " + (message != null ? message : "")).toLowerCase();
        if (path != null && !path.isEmpty()) {
            helper.log(getName(), "DEBUG", "Categorizing with SMB path: " + path);
        }

        if (combined.contains("ransomware") ||
                combined.contains("wannacry") ||
                combined.contains("eternalblue") ||
                combined.contains("ms17-010") ||
                combined.contains("smb_mapping_event")) {
            return "ransomware";
        } else if (combined.contains("malware") || combined.contains("trojan")) {
            return "malware";
        } else if (combined.contains("c2") || combined.contains("command and control")) {
            return "c2_communication";
        } else if (combined.contains("scan")) {
            return "reconnaissance";
        } else if (combined.contains("lateral")) {
            return "lateral_movement";
        } else if (combined.contains("smb")) {
            return "exploit";
        }
        return "network_threat";
    }

    private int calculateThreatScore(String severity, String category) {
        int baseScore;
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

        // Boost for high-risk categories
        if (category.equals("ransomware") || category.equals("c2_communication")) {
            baseScore += 5;
        }

        return Math.min(100, baseScore);
    }

    private String generateAlertId() {
        return "ZEEK-" + System.currentTimeMillis() + "-" +
                String.format("%04d", new Random().nextInt(10000));
    }

    /**
     * Data structure for parsing Zeek notice JSON.
     */
    private static class ZeekNotice {
        @SerializedName("note")
        String note; // Notice type (e.g., Scan::Port_Scan)

        @SerializedName("msg")
        String msg; // Notice message

        @SerializedName("sub")
        String sub; // Sub-message

        @SerializedName("src")
        String src; // Source IP

        @SerializedName("dst")
        String dst; // Destination IP

        @SerializedName("p")
        Integer p; // Port (usually source port)

        @SerializedName("dst_port")
        Integer dstPort; // Destination port

        @SerializedName("proto")
        String proto; // Protocol

        @SerializedName("uid")
        String uid; // Zeek connection UID

        @SerializedName("actions")
        String actions; // Actions taken

        @SerializedName("path")
        String path; // SMB Path
    }
}
