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
 * ZeekModule - SDK-based pluggable module for Zeek NIDS.
 * 
 * Listens for HTTP POSTs containing Zeek JSON logs (from notice.log).
 * Publishes alerts.network.zeek events to the SOAR system.
 */
public class ZeekModule implements PluggableModule {

    private static final String CONFIG_PATH = "config/zeek-module.properties";

    private CoreSystemApi api;
    private ModuleHelper helper;
    private HttpServer httpServer;
    private ExecutorService httpExecutor;

    private String moduleId = "zeek_01";
    private String moduleName = "Zeek NIDS Module";
    private String moduleType = "network_security";

    // Defaults
    private int httpPort = 8091;
    private String httpPath = "/zeek/notices";

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

        helper.log(getName(), "INFO", "Initializing ZeekModule (id=" + moduleId + ")");
        startHttpServer();
        running = true;
        helper.log(getName(), "INFO", "ZeekModule initialized on port " + httpPort);
    }

    @Override
    public void shutdown() {
        running = false;
        if (httpServer != null)
            httpServer.stop(1);
        if (httpExecutor != null)
            httpExecutor.shutdownNow();
        helper.log(getName(), "INFO", "ZeekModule shut down");
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
            httpServer.createContext(httpPath, new ZeekHttpHandler());
            httpExecutor = Executors.newCachedThreadPool();
            httpServer.setExecutor(httpExecutor);
            httpServer.start();
            helper.log(getName(), "INFO", "Listening for Zeek alerts on part " + httpPort + " path " + httpPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Zeek HTTP server", e);
        }
    }

    private void loadConfig() {
        // Simple config loader (optional)
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);
            httpPort = Integer.parseInt(props.getProperty("zeek.http.port", String.valueOf(httpPort)));
            httpPath = props.getProperty("zeek.http.path", httpPath);
        } catch (Exception e) {
            System.out.println("[ZeekModule] Using defaults (config not found): " + e.getMessage());
        }
    }

    private class ZeekHttpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                handleZeekJson(body);
                sendResponse(exchange, 200, "OK");
            } catch (Exception e) {
                helper.log(getName(), "ERROR", "Error parsing Zeek alert: " + e.getMessage());
                sendResponse(exchange, 500, "Error");
            }
        }

        private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void handleZeekJson(String jsonBody) {
        if (jsonBody == null || jsonBody.isEmpty())
            return;

        // Zeek often sends NDJSON or single JSON objects
        // For simplicity, assume single JSON object representing a notice
        ZeekNoticeLog notice = gson.fromJson(jsonBody, ZeekNoticeLog.class);

        if (notice == null || notice.note == null) {
            helper.log(getName(), "WARN", "Invalid Zeek JSON received");
            return;
        }

        publishZeekAlert(notice);
    }

    private void publishZeekAlert(ZeekNoticeLog notice) {
        ZeekAlertData data = new ZeekAlertData();

        data.setAlertId("ZEEK-" + System.currentTimeMillis());
        data.setUid(notice.uid);
        data.setNoticeType(notice.note);
        data.setMsg(notice.msg);
        data.setSubMsg(notice.subMsg);

        // id field
        if (notice.id != null) {
            data.setSourceIp(notice.id.origH);
            data.setSourcePort(notice.id.origP);
            data.setDestinationIp(notice.id.respH);
            data.setDestinationPort(notice.id.respP);
        }

        data.setProtocol(notice.proto);
        data.setPeerDescr(notice.peerDescr);
        data.setActions(notice.actions);
        data.setSuppressFor(notice.suppressFor);

        // Classification & Scoring
        data.setCategory(categorizeNotice(notice.note));
        data.setSeverity(mapSeverity(notice.note));
        data.setThreatScore(calculateScore(data.getSeverity()));
        data.setConfidenceScore(90);

        Event<ZeekAlertData> event = Event.of("alerts.network.zeek", data);
        api.publishEvent(event);

        helper.log(getName(), "INFO", "Published Zeek Alert: " + notice.note + " from " + data.getSourceIp());
    }

    // --- Helpers ---

    private String categorizeNotice(String note) {
        if (note == null)
            return "unknown";
        if (note.contains("Scan"))
            return "scan";
        if (note.contains("SSL") || note.contains("Cert"))
            return "crypto_issue";
        if (note.contains("SSH") || note.contains("Login"))
            return "authentication";
        if (note.contains("Malware") || note.contains("Virus"))
            return "malware";
        return "network_anomaly";
    }

    private String mapSeverity(String note) {
        if (note == null)
            return "low";
        if (note.contains("Malware") || note.contains("Exploit"))
            return "critical";
        if (note.contains("Scan"))
            return "medium";
        return "low";
    }

    private int calculateScore(String severity) {
        switch (severity) {
            case "critical":
                return 90;
            case "high":
                return 75;
            case "medium":
                return 50;
            default:
                return 20;
        }
    }

    // --- JSON Models ---

    private static class ZeekNoticeLog {
        @SerializedName("ts")
        Double ts;
        @SerializedName("uid")
        String uid;
        @SerializedName("id")
        ZeekId id;
        @SerializedName("proto")
        String proto;
        @SerializedName("note")
        String note;
        @SerializedName("msg")
        String msg;
        @SerializedName("sub_msg")
        String subMsg;
        @SerializedName("src")
        String src;
        @SerializedName("dst")
        String dst;
        @SerializedName("p")
        Integer p;
        @SerializedName("peer_descr")
        String peerDescr;
        @SerializedName("actions")
        String actions;
        @SerializedName("suppress_for")
        Double suppressFor;
    }

    private static class ZeekId {
        @SerializedName("orig_h")
        String origH;
        @SerializedName("orig_p")
        Integer origP;
        @SerializedName("resp_h")
        String respH;
        @SerializedName("resp_p")
        Integer respP;
    }
}
