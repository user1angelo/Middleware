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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SuricataHttpModule - SDK-based pluggable module that exposes an HTTP endpoint
 * for Suricata JSON alerts and publishes standardized alerts.network.suricata
 * events via the SOAR SDK.
 *
 * This module is intended to be loaded by the Module Registry & Lifecycle
 * Manager, similar to OpenDaylightModule. It does not connect to RabbitMQ
 * directly; all messaging goes through CoreSystemApi.
 */
public class SuricataHttpModule implements PluggableModule {

    private static final String CONFIG_PATH = "config/suricata-http-module.properties";

    private CoreSystemApi api;
    private ModuleHelper helper;
    private HttpServer httpServer;
    private ExecutorService httpExecutor;

    // Module identity (override via config)
    private String moduleId = "suricata_http_01";
    private String moduleName = "Suricata HTTP NIDS Module";
    @SuppressWarnings("FieldCanBeLocal")
    private String moduleType = "network_security";

    // HTTP listener configuration (override via config)
    private String httpHost = "0.0.0.0"; // informational only; binding is by port
    private int httpPort = 8090;
    private String httpPath = "/suricata/alerts";

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

        helper.log(getName(), "INFO", "Initializing SuricataHttpModule (id=" + moduleId + ")");
        helper.log(getName(), "INFO",
                "HTTP listener configured at host=" + httpHost + ", port=" + httpPort + ", path=" + httpPath);

        startHttpServer();
        running = true;

        helper.log(getName(), "INFO", "SuricataHttpModule initialized and ready to receive alerts");
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
            helper.log(getName(), "INFO", "SuricataHttpModule shut down");
        }
    }

    private void startHttpServer() {
        try {
            // Bind by port only (like PRTGModule); httpHost is used for metadata/logging
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
            httpServer.createContext(httpPath, new SuricataHttpHandler());

            httpExecutor = Executors.newCachedThreadPool();
            httpServer.setExecutor(httpExecutor);
            httpServer.start();

            helper.log(getName(), "INFO",
                    "Started HTTP server on port " + httpPort + " for path " + httpPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Suricata HTTP server on port " + httpPort, e);
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

            httpHost = props.getProperty("suricata.http.host", httpHost);
            httpPort = Integer.parseInt(props.getProperty("suricata.http.port", String.valueOf(httpPort)));
            httpPath = props.getProperty("suricata.http.path", httpPath);

            System.out.println("[SuricataHttpModule] Loaded config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("[SuricataHttpModule][WARN] Could not load config (using defaults): " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // HTTP Handler
    // ---------------------------------------------------------------------

    private class SuricataHttpHandler implements HttpHandler {

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
                        "Received Suricata HTTP request with non-JSON Content-Type=" + contentType);
            }

            String body = readRequestBody(exchange.getRequestBody());

            try {
                handleSuricataJson(body);
                sendResponse(exchange, 200, "OK");
            } catch (Exception e) {
                helper.log(getName(), "ERROR", "Failed to handle Suricata HTTP alert: " + e.getMessage());
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
    // Suricata JSON handling
    // ---------------------------------------------------------------------

    /**
     * Handle a single Suricata JSON event (eve.json-style) received over HTTP.
     */
    private void handleSuricataJson(String jsonBody) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            helper.log(getName(), "WARN", "Received empty Suricata JSON body");
            return;
        }

        SuricataEveLog eveLog = gson.fromJson(jsonBody, SuricataEveLog.class);
        if (eveLog == null) {
            helper.log(getName(), "WARN", "Failed to deserialize Suricata JSON body");
            return;
        }

        // Only process alert events
        if (!"alert".equals(eveLog.eventType)) {
            helper.log(getName(), "DEBUG", "Ignoring non-alert Suricata event_type=" + eveLog.eventType);
            return;
        }

        if (eveLog.alert == null || eveLog.srcIp == null || eveLog.destIp == null) {
            helper.log(getName(), "WARN", "Suricata alert missing required fields; skipping");
            return;
        }

        publishSuricataAlert(eveLog);
    }

    /**
     * Convert Suricata eve.json alert into standardized SuricataAlertData and
     * publish it as an SDK Event.
     */
    private void publishSuricataAlert(SuricataEveLog eveLog) {
        SuricataAlertData payload = new SuricataAlertData();

        // Identification
        payload.setAlertId(generateAlertId());
        payload.setSignatureId(String.valueOf(
                eveLog.alert.signatureId != null ? eveLog.alert.signatureId : 0));
        payload.setSignature(eveLog.alert.signature);

        // Severity mapping
        int severityLevel = eveLog.alert.severity != null ? eveLog.alert.severity : 3;
        payload.setSeverity(mapSeverityLevel(severityLevel));

        // Network context
        payload.setSourceIp(eveLog.srcIp);
        payload.setDestinationIp(eveLog.destIp);
        payload.setSourcePort(eveLog.srcPort != null ? eveLog.srcPort : 0);
        payload.setDestinationPort(eveLog.destPort != null ? eveLog.destPort : 0);
        payload.setProtocol(eveLog.proto != null ? eveLog.proto : "UNKNOWN");

        // Classification
        String category = eveLog.alert.category != null
                ? eveLog.alert.category.toLowerCase().replace(" ", "_")
                : categorizeFromSignature(eveLog.alert.signature);
        payload.setCategory(category);
        payload.setAlertType(category);

        // Threat scores
        payload.setThreatScore(calculateThreatScore(severityLevel, category));
        payload.setConfidenceScore(95);

        // Optional metadata
        if (eveLog.action != null) {
            payload.setAction(eveLog.action);
        }
        if (eveLog.flowId != null) {
            payload.setFlowId(String.valueOf(eveLog.flowId));
        }

        Event<SuricataAlertData> event = Event.of("alerts.network.suricata", payload);
        api.publishEvent(event);

        helper.log(getName(), "INFO",
                String.format(
                        "Published Suricata alert: %s [severity=%s, src=%s:%d, dst=%s:%d, proto=%s]",
                        payload.getSignature(),
                        payload.getSeverity(),
                        payload.getSourceIp(),
                        payload.getSourcePort(),
                        payload.getDestinationIp(),
                        payload.getDestinationPort(),
                        payload.getProtocol()));
    }

    // ---------------------------------------------------------------------
    // Utility methods and eve.json structures
    // ---------------------------------------------------------------------

    private String categorizeFromSignature(String signature) {
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

    private String mapSeverityLevel(int level) {
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

    private int calculateThreatScore(int severity, String category) {
        int baseScore;
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

        if (category != null) {
            String lower = category.toLowerCase();
            if (lower.contains("apt") || lower.contains("ransomware")) {
                baseScore += 10;
            } else if (lower.contains("malware") || lower.contains("trojan")) {
                baseScore += 5;
            }
        }

        return Math.min(100, baseScore);
    }

    private String generateAlertId() {
        return "SURI-" + System.currentTimeMillis();
    }

    /**
     * Data structure for parsing Suricata eve.json log entries (single event).
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
     * Data structure for Suricata alert section within eve.json.
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
