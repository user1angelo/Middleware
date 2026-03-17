package com.nis1.thesis.udm;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.ModuleHelper;
import com.nis1.thesis.sdk.PluggableModule;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

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

    // Automated isolation configuration
    private boolean autoIsolateEnabled = true;
    private int minThreatScore = 75;
    private String severityThreshold = "high"; // "critical" or "high"
    private String[] highRiskCategories = { "ransomware", "apt_activity", "c2_communication", "malware", "lateral_movement" };
    private long isolationCooldownMs = 120000;
    private int isolationCooldownCacheMaxSize = 5000;

    // Alert anti-spam / deduplication
    private boolean dedupEnabled = true;
    private long dedupWindowMs = 30000;
    private int dedupCacheMaxSize = 5000;
    private final ConcurrentHashMap<String, Long> recentAlertTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentIsolationTimestamps = new ConcurrentHashMap<>();

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

            // Load automated isolation configuration
            autoIsolateEnabled = Boolean.parseBoolean(props.getProperty("suricata.auto_isolate.enabled", "true"));
            minThreatScore = Integer.parseInt(props.getProperty("suricata.auto_isolate.min_threat_score", "75"));
            severityThreshold = props.getProperty("suricata.auto_isolate.severity_threshold", "high");
            String categoriesStr = props.getProperty("suricata.auto_isolate.categories",
                    "ransomware,apt_activity,c2_communication,malware");
            highRiskCategories = categoriesStr.split(",");
                isolationCooldownMs = Long.parseLong(props.getProperty("suricata.auto_isolate.cooldown_ms", "120000"));
                isolationCooldownCacheMaxSize = Integer.parseInt(
                    props.getProperty("suricata.auto_isolate.cooldown.max_entries", "5000"));

            dedupEnabled = Boolean.parseBoolean(props.getProperty("suricata.alert_dedup.enabled", "true"));
            dedupWindowMs = Long.parseLong(props.getProperty("suricata.alert_dedup.window_ms", "30000"));
            dedupCacheMaxSize = Integer.parseInt(props.getProperty("suricata.alert_dedup.max_entries", "5000"));

            System.out.println("[SuricataHttpModule] Loaded config from " + CONFIG_PATH);
            System.out.println("[SuricataHttpModule] Auto-isolation enabled: " + autoIsolateEnabled);
                System.out.println("[SuricataHttpModule] Isolation cooldown enabled: " + (isolationCooldownMs > 0)
                    + " (cooldown_ms=" + isolationCooldownMs + ", max_entries=" + isolationCooldownCacheMaxSize + ")");
            System.out.println("[SuricataHttpModule] Alert dedup enabled: " + dedupEnabled +
                    " (window_ms=" + dedupWindowMs + ", max_entries=" + dedupCacheMaxSize + ")");
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

        // Classification - resolve from both Suricata classification and signature
        String category = resolveCategory(eveLog.alert.category, eveLog.alert.signature);
        payload.setCategory(category);
        payload.setAlertType(category);

        // Threat scores
        payload.setThreatScore(calculateThreatScore(severityLevel, category));
        payload.setConfidenceScore(95);

        if (!shouldPublishAlert(payload)) {
            helper.log(getName(), "DEBUG",
                String.format("Suppressed duplicate Suricata alert in dedup window: %s [%s:%d -> %s:%d %s]",
                    payload.getSignature(),
                    payload.getSourceIp(), payload.getSourcePort(),
                    payload.getDestinationIp(), payload.getDestinationPort(),
                    payload.getProtocol()));
            return;
        }

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

        // Automated isolation logic
        if (shouldTriggerIsolation(payload)) {
            triggerAutomatedIsolation(payload);
        }
    }

    /**
     * Evaluate whether an alert should trigger automated isolation.
     * 
     * @param alert The Suricata alert data to evaluate
     * @return true if isolation should be triggered
     */
    private boolean shouldTriggerIsolation(SuricataAlertData alert) {
        if (!autoIsolateEnabled) {
            helper.log(getName(), "DEBUG", "Auto-isolation is disabled");
            return false;
        }

        // Check threat score threshold
        if (alert.getThreatScore() == null || alert.getThreatScore() < minThreatScore) {
            helper.log(getName(), "DEBUG",
                    String.format("Alert threat score (%d) below threshold (%d)",
                            alert.getThreatScore(), minThreatScore));
            return false;
        }

        // Check severity threshold
        String severity = alert.getSeverity();
        boolean severityMet = false;
        if ("critical".equalsIgnoreCase(severity)) {
            severityMet = true;
        } else if ("high".equalsIgnoreCase(severity) &&
                ("high".equalsIgnoreCase(severityThreshold) || "medium".equalsIgnoreCase(severityThreshold))) {
            severityMet = true;
        }

        if (!severityMet) {
            helper.log(getName(), "DEBUG",
                    String.format("Alert severity (%s) does not meet threshold (%s)",
                            severity, severityThreshold));
            return false;
        }

        // Check if category is high-risk
        String category = alert.getCategory();
        if (category != null) {
            for (String riskCategory : highRiskCategories) {
                if (category.toLowerCase().contains(riskCategory.toLowerCase().trim())) {
                    helper.log(getName(), "INFO",
                            String.format("ISOLATION CRITERIA MET: score=%d, severity=%s, category=%s",
                                    alert.getThreatScore(), severity, category));
                    return true;
                }
            }
        }

        helper.log(getName(), "DEBUG",
                String.format("Alert category (%s) not in high-risk list", category));
        return false;
    }

    /**
     * Trigger automated isolation by publishing a mitigation command.
     * 
     * @param alert The alert that triggered the isolation
     */
    private void triggerAutomatedIsolation(SuricataAlertData alert) {
        String targetHost = alert.getSourceIp();

        if (!shouldPublishIsolationForHost(targetHost)) {
            helper.log(getName(), "INFO",
                    String.format("Suppressed duplicate auto-isolation for %s inside cooldown window (%d ms)",
                            targetHost, isolationCooldownMs));
            return;
        }

        String justification = String.format(
                "Automated isolation: Suricata detected %s (severity=%s, score=%d, sig=%s)",
                alert.getCategory(),
                alert.getSeverity(),
                alert.getThreatScore(),
                alert.getSignature());

        helper.log(getName(), "WARN",
                String.format("🚨 AUTO-ISOLATION TRIGGERED for %s - %s",
                        targetHost, justification));

        // Publish mitigation command using SDK helper
        helper.publishMitigationCommand(
                targetHost,
                com.nis1.thesis.sdk.MitigationAction.ISOLATE_VLAN,
                justification);

        helper.log(getName(), "INFO",
                String.format("Published mitigation command: ISOLATE_VLAN for %s", targetHost));
    }

    private boolean shouldPublishIsolationForHost(String targetHost) {
        if (targetHost == null || targetHost.isBlank()) {
            helper.log(getName(), "WARN", "Auto-isolation skipped because source host is missing");
            return false;
        }

        if (isolationCooldownMs <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        cleanupExpiredIsolationEntries(now);

        String key = targetHost.trim();
        Long previousTimestamp = recentIsolationTimestamps.putIfAbsent(key, now);
        if (previousTimestamp == null) {
            return true;
        }

        if ((now - previousTimestamp) <= isolationCooldownMs) {
            return false;
        }

        recentIsolationTimestamps.put(key, now);
        return true;
    }

    // ---------------------------------------------------------------------
    // Utility methods and eve.json structures
    // ---------------------------------------------------------------------

    /**
     * Resolve the best category by considering both Suricata classification and signature.
     * Maps known Suricata classifications to specific event types, and falls through
     * to signature-based categorization for generic classifications.
     */
    private String resolveCategory(String suricataClassification, String signature) {
        // 1. Try to get a specific category from the Suricata classification
        if (suricataClassification != null && !suricataClassification.isBlank()) {
            String mapped = mapSuricataClassification(suricataClassification);
            if (mapped != null) {
                return mapped;
            }
        }

        // 2. Fall through to signature-based categorization
        return categorizeFromSignature(signature);
    }

    /**
     * Map Suricata classification strings to specific, meaningful event type categories.
     * Returns null for generic/vague classifications that should be resolved by signature.
     */
    private String mapSuricataClassification(String classification) {
        if (classification == null) return null;
        String lower = classification.toLowerCase().trim();

        // Specific, meaningful classifications
        if (lower.contains("user privilege gain") || lower.contains("admin privilege gain")) {
            return "attempted_privilege_gain";
        } else if (lower.contains("administrator privilege")) {
            return "attempted_admin_privilege_gain";
        } else if (lower.contains("network trojan")) {
            return "network_trojan_detected";
        } else if (lower.contains("web application attack")) {
            return "web_application_attack";
        } else if (lower.contains("attempted denial of service")) {
            return "denial_of_service";
        } else if (lower.contains("successful user privilege gain")) {
            return "successful_privilege_gain";
        } else if (lower.contains("successful admin")) {
            return "successful_admin_privilege_gain";
        } else if (lower.contains("trojan activity") || lower.contains("a]trojan")) {
            return "malware";
        } else if (lower.contains("exploit kit")) {
            return "exploit";
        } else if (lower.contains("attempted information leak") || lower.contains("information leak")) {
            return "information_leak";
        } else if (lower.contains("policy violation")) {
            return "policy_violation";
        }

        // Generic / vague classifications → return null to fall through to signature-based
        if (lower.contains("potentially bad traffic")
                || lower.contains("misc activity")
                || lower.contains("misc attack")
                || lower.contains("not suspicious")
                || lower.contains("unknown")) {
            return null; // let categorizeFromSignature() handle it
        }

        // Default: normalize the classification as-is for any unmapped specific ones
        return classification.toLowerCase().replace(" ", "_");
    }

    private String categorizeFromSignature(String signature) {
        if (signature == null)
            return "unknown";
        String lower = signature.toLowerCase();

        // Lateral movement detection (SMB-based tools and techniques)
        if (lower.contains("lateral movement") || lower.contains("lateral_movement")
                || (lower.contains("smb") && (lower.contains("wmi") || lower.contains("wmic")
                        || lower.contains("rundll") || lower.contains("psexec")
                        || lower.contains("ipconfig") || lower.contains(".mof")
                        || lower.contains("mof ") || lower.contains("managed object"))))
        {
            return "lateral_movement";
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
            if (lower.contains("apt") || lower.contains("ransomware")
                    || lower.contains("lateral_movement") || lower.contains("privilege_gain")) {
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

    private boolean shouldPublishAlert(SuricataAlertData alert) {
        if (!dedupEnabled || dedupWindowMs <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        cleanupExpiredDedupEntries(now);

        String alertKey = buildAlertDedupKey(alert);
        Long previousTimestamp = recentAlertTimestamps.putIfAbsent(alertKey, now);
        if (previousTimestamp == null) {
            return true;
        }

        if ((now - previousTimestamp) <= dedupWindowMs) {
            return false;
        }

        recentAlertTimestamps.put(alertKey, now);
        return true;
    }

    private String buildAlertDedupKey(SuricataAlertData alert) {
        String signature = alert.getSignature() != null ? alert.getSignature().toLowerCase() : "unknown_signature";
        String sourceIp = alert.getSourceIp() != null ? alert.getSourceIp() : "unknown_src";
        String destinationIp = alert.getDestinationIp() != null ? alert.getDestinationIp() : "unknown_dst";
        String protocol = alert.getProtocol() != null ? alert.getProtocol().toUpperCase() : "UNKNOWN";

        return signature + "|" + sourceIp + "|" + alert.getSourcePort() + "|" +
                destinationIp + "|" + alert.getDestinationPort() + "|" + protocol;
    }

    private void cleanupExpiredDedupEntries(long now) {
        long cutoff = now - dedupWindowMs;
        Iterator<Map.Entry<String, Long>> iterator = recentAlertTimestamps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() < cutoff) {
                iterator.remove();
            }
        }

        if (recentAlertTimestamps.size() > dedupCacheMaxSize) {
            recentAlertTimestamps.clear();
        }
    }

    private void cleanupExpiredIsolationEntries(long now) {
        long cutoff = now - isolationCooldownMs;
        Iterator<Map.Entry<String, Long>> iterator = recentIsolationTimestamps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() < cutoff) {
                iterator.remove();
            }
        }

        if (recentIsolationTimestamps.size() > isolationCooldownCacheMaxSize) {
            recentIsolationTimestamps.clear();
        }
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
