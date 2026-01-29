package com.nis1.thesis.udm;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.MitigationAction;
import com.nis1.thesis.sdk.MitigationCommandData;
import com.nis1.thesis.sdk.ModuleHelper;
import com.nis1.thesis.sdk.PluggableModule;
import com.nis1.thesis.udm.services.NetworkScannerService;
import com.nis1.thesis.udm.services.OpenDaylightClient;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

/**
 * OpenDaylightModule - Integrates OpenDaylight SDN capabilities into the
 * System.
 *
 * Capabilities:
 * 1. Network Topology Discovery (Active Scanning)
 * 2. Automatic Host Isolation (via ODL RESTCONF)
 * 3. Manual Mitigation Execution
 */
public class OpenDaylightModule implements PluggableModule {

    private static final String CONFIG_PATH = "config/opendaylight-module.properties";

    private CoreSystemApi api;
    private ModuleHelper helper;
    private NetworkScannerService scanner;
    private OpenDaylightClient odlClient;

    // Module identity
    private String moduleId = "odl_sdn_01";
    private String moduleName = "OpenDaylight SDN Module";

    // Config
    private String odlBaseUrl = "http://localhost:8181";
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

        // Initialize services
        this.scanner = new NetworkScannerService(helper, getName());
        this.odlClient = new OpenDaylightClient(helper, getName(), odlBaseUrl, odlUsername, odlPassword);

        this.running = true;

        helper.log(getName(), "INFO", "Initializing OpenDaylightModule...");
        helper.log(getName(), "INFO", "Connected to ODL at: " + odlBaseUrl);

        // Subscribe to events
        api.subscribeToEvent("INITIATE_MITIGATION", this::onMitigationCommand);
        api.subscribeToEvent("odl.topology.discover", this::onTopologyDiscover);
        api.subscribeToEvent("ODL_TOPOLOGY_DISCOVER", this::onTopologyDiscover);

        helper.log(getName(), "INFO", "Subscribed to INITIATE_MITIGATION & ODL_TOPOLOGY_DISCOVER");
    }

    @Override
    public void shutdown() {
        running = false;
        helper.log(getName(), "INFO", "Shutting down OpenDaylightModule");
    }

    /**
     * Handle Manual/Automatic Mitigation Requests
     */
    private void onMitigationCommand(Event<?> event) {
        if (!running)
            return;

        try {
            Object data = event.getData();
            if (!(data instanceof MitigationCommandData)) {
                return;
            }

            MitigationCommandData command = (MitigationCommandData) data;
            String targetHost = command.getTargetHost();
            MitigationAction action = command.getAction();

            helper.log(getName(), "INFO", "Received mitigation request: " + action + " for " + targetHost);

            if (action == MitigationAction.BLOCK_IP ||
                    action == MitigationAction.QUARANTINE ||
                    action == MitigationAction.ISOLATE_VLAN) {

                boolean success = odlClient.isolateHost(targetHost);
                if (success) {
                    helper.log(getName(), "INFO", "Successfully isolated host: " + targetHost);
                } else {
                    helper.log(getName(), "ERROR", "Failed to isolate host: " + targetHost);
                }
            } else {
                helper.log(getName(), "WARN", "Action " + action + " not supported by ODL module yet.");
            }

        } catch (Exception e) {
            helper.log(getName(), "ERROR", "Error handling mitigation: " + e.getMessage());
        }
    }

    /**
     * Handle Topology Discovery (Ping Sweep)
     */
    private void onTopologyDiscover(Event<?> event) {
        if (!running)
            return;

        helper.log(getName(), "INFO", "Starting network topology scan...");

        String startIp = null;
        Object data = event.getData();

        // Extract start_ip from payload if available
        if (data instanceof JSONObject) {
            JSONObject json = (JSONObject) data;
            JSONObject payload = json.optJSONObject("payload");
            if (payload != null) {
                startIp = payload.optString("start_ip", null);
                if (startIp != null && startIp.isEmpty())
                    startIp = null;
            } else {
                // Direct payload might be the params
                startIp = json.optString("start_ip", null);
            }
        }

        // Run scan in background (though helper.log might block, the event handler is
        // usually async)
        // Ideally should perform in a separate thread if scanning takes long,
        // but ExecutorService is managed inside NetworkScannerService.

        Map<String, String> results = scanner.scanNetwork(startIp);

        helper.log(getName(), "INFO", "Scan complete. Found " + results.size() + " hosts.");

        // Log discovered hosts
        for (Map.Entry<String, String> entry : results.entrySet()) {
            helper.log(getName(), "INFO", "  Host: " + entry.getKey() + " -> MAC: " + entry.getValue());
        }
    }

    // ========== Public Methods for Manual Control ==========

    /**
     * Manually isolate a host by IP address.
     * Can be called directly or via events.
     *
     * @param ipAddress The IP address to isolate
     * @return true if isolation was successful
     */
    public boolean manualIsolateHost(String ipAddress) {
        helper.log(getName(), "INFO", "Manual isolation requested for: " + ipAddress);
        boolean success = odlClient.isolateHost(ipAddress);
        if (success) {
            helper.log(getName(), "INFO", "Manual isolation SUCCESS for: " + ipAddress);
        } else {
            helper.log(getName(), "ERROR", "Manual isolation FAILED for: " + ipAddress);
        }
        return success;
    }

    /**
     * Remove isolation from a host.
     *
     * @param ipAddress The IP address to un-isolate
     * @return true if removal was successful
     */
    public boolean manualRemoveIsolation(String ipAddress) {
        helper.log(getName(), "INFO", "Remove isolation requested for: " + ipAddress);
        boolean success = odlClient.removeIsolation(ipAddress);
        if (success) {
            helper.log(getName(), "INFO", "Isolation removed for: " + ipAddress);
        } else {
            helper.log(getName(), "ERROR", "Failed to remove isolation for: " + ipAddress);
        }
        return success;
    }

    /**
     * Trigger a network scan manually.
     *
     * @param startIp Optional start IP for the scan range
     * @return Map of discovered hosts (IP -> MAC)
     */
    public Map<String, String> manualScanNetwork(String startIp) {
        helper.log(getName(), "INFO", "Manual network scan triggered");
        return scanner.scanNetwork(startIp);
    }

    private void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);
            odlBaseUrl = props.getProperty("odl.base_url", odlBaseUrl);
            odlUsername = props.getProperty("odl.username", odlUsername);
            odlPassword = props.getProperty("odl.password", odlPassword);
            moduleId = props.getProperty("module.id", moduleId);
            moduleName = props.getProperty("module.name", moduleName);
        } catch (IOException e) {
            System.out.println("[OpenDaylightModule] Using default config");
        }
    }
}
