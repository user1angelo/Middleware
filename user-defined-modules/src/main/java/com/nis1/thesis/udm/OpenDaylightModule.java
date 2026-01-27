package com.nis1.thesis.udm;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.MitigationAction;
import com.nis1.thesis.sdk.MitigationCommandData;
import com.nis1.thesis.sdk.ModuleHelper;
import com.nis1.thesis.sdk.PluggableModule;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * OpenDaylightModule - SDK-based pluggable module for SDN mitigation.
 *
 * This implementation is designed to be loaded by the Module Registry & Lifecycle Manager
 * via the SOAR SDK. It no longer connects directly to RabbitMQ or sends registration
 * messages itself. Instead, it relies on the CoreSystemApi to subscribe to mitigation-
 * related events and to publish any future response events.
 *
 * For now, the module provides a **stubbed implementation** that:
 * - Loads OpenDaylight connection settings from a dedicated properties file
 * - Subscribes to INITIATE_MITIGATION events via CoreSystemApi
 * - Logs how it would translate those commands into RESTCONF calls to OpenDaylight
 *
 * Once the RESTCONF details are finalized, the simulate* methods can be replaced with
 * real HTTP calls using the configured base URL and credentials.
 */
public class OpenDaylightModule implements PluggableModule {

    private static final String CONFIG_PATH = "config/opendaylight-module.properties";

    private CoreSystemApi api;
    private ModuleHelper helper;

    // Module identity (override via config)
    private String moduleId = "odl_sdn_01";
    private String moduleName = "OpenDaylight SDN Module";
    @SuppressWarnings("FieldCanBeLocal")
    private String moduleType = "sdn_controller";

    // OpenDaylight RESTCONF settings (override via config)
    private String odlBaseUrl = "http://opendaylight:8181";
    private String odlUsername = "admin";
    private String odlPassword = "admin";

    private volatile boolean running = false;

    @Override
    public String getName() {
        return moduleName;
    }

    @Override
    public void initialize(CoreSystemApi api) {
        this.api = api;
        this.helper = new ModuleHelper(api);

        loadConfig();
        this.running = true;

        helper.log(getName(), "INFO", "Initializing OpenDaylightModule (id=" + moduleId + ")");
        helper.log(getName(), "INFO", "Using OpenDaylight base URL: " + odlBaseUrl);

        // Subscribe to mitigation commands published by the Workflow Engine
        api.subscribeToEvent("INITIATE_MITIGATION", this::onMitigationCommand);

        // Optional future subscription for more SDN-specific flows
        api.subscribeToEvent("SDN_INSTALL_FLOW", this::onSdnInstallFlow);

        helper.log(getName(), "INFO", "Subscriptions registered for INITIATE_MITIGATION and SDN_INSTALL_FLOW");
    }

    @Override
    public void shutdown() {
        running = false;
        if (helper != null) {
            helper.log(getName(), "INFO", "Shutting down OpenDaylightModule");
        }
    }

    /**
     * Handle INITIATE_MITIGATION events from the Workflow Engine.
     * Expected payload type: MitigationCommandData (from the SDK).
     */
    private void onMitigationCommand(Event<?> event) {
        if (!running) {
            return;
        }

        Object data = event.getData();
        if (!(data instanceof MitigationCommandData)) {
            helper.log(getName(), "WARN",
                    "Received INITIATE_MITIGATION with unexpected payload type: " +
                            (data == null ? "null" : data.getClass().getName()));
            return;
        }

        MitigationCommandData command = (MitigationCommandData) data;
        String targetHost = command.getTargetHost();
        MitigationAction action = command.getAction();
        String justification = command.getJustification();

        helper.log(getName(), "INFO",
                String.format("Handling mitigation command %s for target %s (workflow=%s, reason=%s)",
                        action,
                        targetHost,
                        command.getWorkflowInstanceId(),
                        justification));

        try {
            if (action == MitigationAction.BLOCK_IP ||
                action == MitigationAction.QUARANTINE ||
                action == MitigationAction.ISOLATE_VLAN) {
                simulateBlockIp(targetHost, action);
            } else {
                helper.log(getName(), "WARN",
                        "MitigationAction " + action + " is not yet implemented in OpenDaylightModule stub");
            }
        } catch (Exception e) {
            helper.log(getName(), "ERROR",
                    "Error while simulating mitigation for target " + targetHost + ": " + e.getMessage());
        }
    }

    /**
     * Handle SDN_INSTALL_FLOW events.
     * In this stub version we simply log the received payload.
     */
    private void onSdnInstallFlow(Event<?> event) {
        if (!running) {
            return;
        }

        Object data = event.getData();
        helper.log(getName(), "INFO",
                "Received SDN_INSTALL_FLOW event with payload type=" +
                        (data == null ? "null" : data.getClass().getName()));
        helper.log(getName(), "DEBUG",
                "SDN_INSTALL_FLOW payload (to be mapped to RESTCONF in future): " + String.valueOf(data));
    }

    /**
     * Build a Flow payload for dropping traffic from the target host and send it to ODL.
     */
    private void simulateBlockIp(String targetHost, MitigationAction action) {
        if (targetHost == null || targetHost.isBlank()) {
            helper.log(getName(), "WARN", "No targetHost provided for mitigation; skipping");
            return;
        }

        // Generic Flow ID derived from host
        String flowId = "block-" + targetHost.replace(".", "-");
        
        // 1. Construct the JSON payload for an OpenFlow "drop" action.
        // This schema matches generic ODL /restconf/config/opendaylight-inventory:nodes/node/{id}/table/{id}/flow/{id}
        // Adjust fields if your ODL version uses a different model (e.g. Sodium/Magnesium+).
        String jsonPayload = String.format(
            "{\n" +
            "  \"flow\": [\n" +
            "    {\n" +
            "      \"id\": \"%s\",\n" +
            "      \"table_id\": 0,\n" +
            "      \"priority\": 100,\n" +
            "      \"hard-timeout\": 0,\n" +
            "      \"idle-timeout\": 0,\n" +
            "      \"match\": {\n" +
            "        \"ipv4-source\": \"%s/32\",\n" +
            "        \"ethernet-match\": {\n" +
            "          \"ethernet-type\": {\n" +
            "            \"type\": 2048\n" +
            "          }\n" +
            "        }\n" +
            "      },\n" +
            "      \"instructions\": {\n" +
            "        \"instruction\": [\n" +
            "          {\n" +
            "            \"order\": 0,\n" +
            "            \"apply-actions\": {\n" +
            "              \"action\": [\n" +
            "                {\n" +
            "                  \"order\": 0,\n" +
            "                  \"drop-action\": {}\n" +
            "                }\n" +
            "              ]\n" +
            "            }\n" +
            "          }\n" +
            "        ]\n" +
            "      }\n" +
            "    }\n" +
            "  ]\n" +
            "}", flowId, targetHost);

        // 2. Build the URL. We assume a single switch "openflow:1" for valid simplicity, 
        // or we could iterate switches. For this stub->impl transition, we'll hardcode or config it later.
        // Path: /restconf/config/opendaylight-inventory:nodes/node/openflow:1/table/0/flow/{flowId}
        String nodeName = "openflow:1"; // default
        String path = String.format("/restconf/config/opendaylight-inventory:nodes/node/%s/table/0/flow/%s", nodeName, flowId);

        helper.log(getName(), "INFO", "Sending RESTCONF request to " + odlBaseUrl + path);
        
        // 3. Send Request
        try {
            sendRestconfRequest("PUT", path, jsonPayload);
            helper.log(getName(), "INFO", "Successfully installed DROP flow for " + targetHost);
        } catch (IOException e) {
            helper.log(getName(), "ERROR", "Failed to send RESTCONF request: " + e.getMessage());
            // Log the payload for debug
             helper.log(getName(), "DEBUG", "Failed Payload: " + jsonPayload);
        }
    }

    private void sendRestconfRequest(String method, String path, String jsonPayload) throws IOException {
        String fullUrl = odlBaseUrl + path;
        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        // Auth
        String auth = odlUsername + ":" + odlPassword;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + encodedAuth);

        // Headers
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        // Write Body
        if (jsonPayload != null) {
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
        }

        // Read Response
        int status = conn.getResponseCode();
        if (status >= 200 && status < 300) {
            // Success
            return;
        } else {
            // Error - read stream
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                throw new IOException("HTTP " + status + ": " + response.toString());
            }
        }
    }

    /**
     * Load module and OpenDaylight configuration from properties file.
     */
    private void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);

            moduleId = props.getProperty("module.id", moduleId);
            moduleName = props.getProperty("module.name", moduleName);
            moduleType = props.getProperty("module.type", moduleType);

            odlBaseUrl = props.getProperty("odl.base_url", odlBaseUrl);
            odlUsername = props.getProperty("odl.username", odlUsername);
            odlPassword = props.getProperty("odl.password", odlPassword);

            System.out.println("[OpenDaylightModule] Loaded config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("[OpenDaylightModule][WARN] Could not load config (using defaults): " + e.getMessage());
        }
    }
}
