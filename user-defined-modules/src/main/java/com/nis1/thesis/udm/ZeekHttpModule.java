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
 * ZeekHttpModule - SDK-based pluggable module that exposes an HTTP endpoint
 * for Zeek JSON 'notice' logs and publishes standardized alerts.network.zeek
 * events via the SOAR SDK.
 */
public class ZeekHttpModule implements PluggableModule {

    private static final String CONFIG_PATH = "config/zeek-http-module.properties";

    private CoreSystemApi api;
    private ModuleHelper helper;
    private HttpServer httpServer;
    private ExecutorService httpExecutor;

    // Module identity (override via config)
    private String moduleId = "zeek_http_01";
    private String moduleName = "Zeek HTTP IDS Module";
    @SuppressWarnings("FieldCanBeLocal")
    private String moduleType = "network_security";

    // HTTP listener configuration (override via config)
    private String httpHost = "0.0.0.0";
    private int httpPort = 8091; // Distinct from Suricata's 8090
    private String httpPath = "/zeek/alerts";

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

        helper.log(getName(), "INFO", "ZeekHttpModule initialized and ready to receive alerts");
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
                helper.log(getName(), "ERROR", "Failed to handle Zeek HTTP alert: " + e.getMessage());
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

    private void handleZeekJson(String jsonBody) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            helper.log(getName(), "WARN", "Received empty Zeek JSON body");
            return;
        }

        // Zeek often sends 'notice.log' entries.
        // We attempt to map to a generic ZeekNotice structure.
        ZeekNoticeLog notice = gson.fromJson(jsonBody, ZeekNoticeLog.class);

        if (notice == null || notice.uid == null) {
            helper.log(getName(), "WARN", "Failed to deserialize Zeek JSON body or missing 'uid'");
            return;
        }

        publishZeekAlert(notice);
    }

    private void publishZeekAlert(ZeekNoticeLog notice) {
        ZeekAlertData payload = new ZeekAlertData();

        // Identification
        payload.setAlertId("ZEEK-" + System.currentTimeMillis() + "-" + notice.uid);
        payload.setConnectionUid(notice.uid);
        payload.setNote(notice.note != null ? notice.note : "Unknown");

        // Network Context - handle id.orig_h / id.resp_h
        if (notice.id != null) {
            payload.setSourceIp(notice.id.orig_h);
            payload.setDestinationIp(notice.id.resp_h);
            payload.setSourcePort(notice.id.orig_p != null ? notice.id.orig_p : 0);
            payload.setDestinationPort(notice.id.resp_p != null ? notice.id.resp_p : 0);
        } else {
            // Fallback if 'id' object is flattened or distinct
            payload.setSourceIp(notice.src);
            payload.setDestinationIp(notice.dst);
            payload.setSourcePort(notice.p != null ? notice.p : 0);
            // assume generic port if flattened not available
        }

        payload.setProtocol(notice.proto != null ? notice.proto : "tcp"); // Default to tcp if missing

        // Classification
        payload.setMsg(notice.msg);
        payload.setSub(notice.sub);

        // Severity & Threat Score Logic
        String severity = "medium"; // default
        int score = 50;

        if (notice.note != null) {
            String noteLower = notice.note.toLowerCase();
            if (noteLower.contains("scan") || noteLower.contains("recon")) {
                severity = "medium";
                score = 40;
            } else if (noteLower.contains("drop") || noteLower.contains("exploit")) {
                severity = "high";
                score = 80;
            } else if (noteLower.contains("ssh") || noteLower.contains("login")) {
                severity = "high";
                score = 70;
            } else if (noteLower.contains("bad") && noteLower.contains("checksum")) {
                severity = "low";
                score = 10;
            }
        }

        payload.setSeverity(severity);
        payload.setThreatScore(score);
        payload.setConfidenceScore(90);

        Event<ZeekAlertData> event = Event.of("alerts.network.zeek", payload);
        api.publishEvent(event);

        helper.log(getName(), "INFO",
                String.format(
                        "Published Zeek alert: %s [uid=%s, src=%s, dst=%s, note=%s]",
                        payload.getMsg(),
                        payload.getConnectionUid(),
                        payload.getSourceIp(),
                        payload.getDestinationIp(),
                        payload.getNote()));
    }

    /**
     * Parsing Classes for Zeek JSON
     */
    private static class ZeekNoticeLog {
        // Standard Zeek JSON fields
        @SerializedName("ts")
        double ts;
        @SerializedName("uid")
        String uid;

        @SerializedName("id.orig_h")
        String id_orig_h; // flat version sometimes
        @SerializedName("id.resp_h")
        String id_resp_h;

        @SerializedName("id")
        ZeekConnId id; // nested version

        @SerializedName("proto")
        String proto;
        @SerializedName("note")
        String note;
        @SerializedName("msg")
        String msg;
        @SerializedName("sub")
        String sub;
        @SerializedName("src")
        String src;
        @SerializedName("dst")
        String dst;
        @SerializedName("p")
        Integer p;
        @SerializedName("peer_descr")
        String peer_descr;
        @SerializedName("actions")
        String[] actions;
        @SerializedName("suppress_for")
        Double suppress_for;
        @SerializedName("dropped")
        Boolean dropped;
    }

    private static class ZeekConnId {
        @SerializedName("orig_h")
        String orig_h;
        @SerializedName("orig_p")
        Integer orig_p;
        @SerializedName("resp_h")
        String resp_h;
        @SerializedName("resp_p")
        Integer resp_p;
    }
}
