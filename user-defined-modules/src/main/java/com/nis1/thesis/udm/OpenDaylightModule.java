package com.nis1.thesis.udm;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.MitigationAction;
import com.nis1.thesis.sdk.MitigationCommandData;
import com.nis1.thesis.sdk.ModuleHelper;
import com.nis1.thesis.sdk.PluggableModule;
import com.nis1.thesis.udm.services.NetworkScannerService;
import com.nis1.thesis.udm.services.OpenDaylightClient;

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

    private final Map<String, ActiveMitigation> activeMitigations = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> mitigationTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService mitigationScheduler = Executors.newSingleThreadScheduledExecutor();

    // Module identity
    private String moduleId = "odl_sdn_01";
    private String moduleName = "OpenDaylight SDN Module";

    // Config
    private String odlBaseUrl = "http://localhost:8181";
    private String odlUsername = "admin";
    private String odlPassword = "admin";
    private String quarantineDefaultNode = "openflow:1";
    private boolean quarantineContainArp = false;
    private boolean quarantineContainDhcp = false;

    private volatile boolean running = false;

    private static class ActiveMitigation {
        private final String mitigationId;
        private final String targetHost;
        private final String targetMac;
        private final OpenDaylightClient.IsolationPolicy policy;
        private final String owner;
        private final long createdAtMs;
        private volatile long expiresAtMs;

        ActiveMitigation(String mitigationId, String targetHost, String targetMac,
                OpenDaylightClient.IsolationPolicy policy, String owner, long createdAtMs, long expiresAtMs) {
            this.mitigationId = mitigationId;
            this.targetHost = targetHost;
            this.targetMac = targetMac;
            this.policy = policy;
            this.owner = owner;
            this.createdAtMs = createdAtMs;
            this.expiresAtMs = expiresAtMs;
        }
    }

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
        this.odlClient = new OpenDaylightClient(
            helper,
            getName(),
            odlBaseUrl,
            odlUsername,
            odlPassword,
            quarantineDefaultNode,
            quarantineContainArp,
            quarantineContainDhcp);

        this.running = true;

        helper.log(getName(), "INFO", "Initializing OpenDaylightModule... [VERSION 2.0 CHECK]");
        helper.log(getName(), "INFO", "Connected to ODL at: " + odlBaseUrl);

        // Subscribe to events
        api.subscribeToEvent("odl.topology.discover", this::onTopologyDiscover);
        api.subscribeToEvent("ODL_TOPOLOGY_DISCOVER", this::onTopologyDiscover);
        api.subscribeToEvent("INITIATE_MITIGATION", this::onMitigationCommand);
        api.subscribeToEvent("REMOVE_MITIGATION", this::onRemoveMitigation);
        api.subscribeToEvent("INSTALL_PROACTIVE_POLICY", this::onInstallProactivePolicy);

        helper.log(getName(), "INFO",
                "Subscribed to INITIATE_MITIGATION, REMOVE_MITIGATION, INSTALL_PROACTIVE_POLICY & ODL_TOPOLOGY_DISCOVER");
    }

    @Override
    public void shutdown() {
        running = false;
        mitigationTimers.values().forEach(timer -> timer.cancel(false));
        mitigationTimers.clear();
        activeMitigations.clear();
        mitigationScheduler.shutdownNow();
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
            JSONObject payload = parseCommandPayload(command);
            String mitigationId = payload.optString("mitigation_id", "mit-" + UUID.randomUUID());
            String owner = payload.optString("owner", "workflow");
            long durationMs = extractDurationMs(payload);
            boolean autoExpire = payload.optBoolean("auto_expire", false);
            String targetMac = payload.optString("targetMac", payload.optString("target_mac", ""));
            OpenDaylightClient.IsolationPolicy policy = parseIsolationPolicy(payload);

            if ((targetHost == null || targetHost.isEmpty()) && payload.has("targetHost")) {
                targetHost = payload.optString("targetHost");
            }

            helper.log(getName(), "INFO", "Received mitigation request: " + action + " for " + targetHost);
            helper.log(getName(), "DEBUG", "Mitigation payload: " + payload);

            if (action == MitigationAction.BLOCK_IP ||
                    action == MitigationAction.QUARANTINE ||
                    action == MitigationAction.ISOLATE_VLAN) {

                OpenDaylightClient.IsolationResult result =
                    odlClient.isolateHost(targetHost, targetMac, mitigationId, policy);
                boolean success = result.success;
                if (success) {
                    helper.log(getName(), "INFO", "Successfully isolated host: " + targetHost);
                    helper.log(getName(), "INFO", "Isolation localization: node=" + result.localization.nodeId
                        + ", port=" + result.localization.portId
                        + ", status=" + result.localization.status
                        + ", source=" + result.localization.sourceOfTruth);
                    helper.log(getName(), "INFO", "Policy rule counts: drop=" + result.dropRules
                        + ", allowlist=" + result.allowRules);
                    registerMitigation(mitigationId, targetHost, targetMac, policy, owner, autoExpire, durationMs);
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
     * Handle Remove Mitigation Requests
     */
    private void onRemoveMitigation(Event<?> event) {
        if (!running)
            return;

        try {
            Object data = event.getData();
            if (!(data instanceof MitigationCommandData)) {
                return;
            }

            MitigationCommandData command = (MitigationCommandData) data;
            JSONObject payload = parseCommandPayload(command);
            String mitigationId = payload.optString("mitigation_id", null);
            String targetHost = command.getTargetHost();
            String targetMac = payload.optString("targetMac", payload.optString("target_mac", ""));
            OpenDaylightClient.IsolationPolicy policy = null;

            if ((targetHost == null || targetHost.isEmpty()) && payload.has("targetHost")) {
                targetHost = payload.optString("targetHost");
            }

            if (mitigationId != null && !mitigationId.isEmpty()) {
                ActiveMitigation record = activeMitigations.get(mitigationId);
                if (record == null) {
                    helper.log(getName(), "WARN",
                            "Ignoring remove request for unknown mitigation_id: " + mitigationId);
                    return;
                }
                if (targetHost == null || targetHost.isEmpty()) {
                    targetHost = record.targetHost;
                }
                if (targetMac == null || targetMac.isEmpty()) {
                    targetMac = record.targetMac;
                }
                policy = record.policy;
            }

            if ((targetHost == null || targetHost.isEmpty()) && (targetMac == null || targetMac.isEmpty())) {
                helper.log(getName(), "ERROR", "Cannot remove mitigation: missing target host and target mac");
                return;
            }

            helper.log(getName(), "INFO", "Received remove mitigation request for "
                    + (targetHost != null && !targetHost.isEmpty() ? targetHost : targetMac));

            boolean success = odlClient.removeIsolation(targetHost, targetMac, mitigationId, policy);
            if (success) {
                helper.log(getName(), "INFO", "Successfully removed isolation from host: " + targetHost);
                if (mitigationId != null && !mitigationId.isEmpty()) {
                    cancelMitigationTimer(mitigationId);
                    activeMitigations.remove(mitigationId);
                }
            } else {
                helper.log(getName(), "ERROR", "Failed to remove isolation from host: " + targetHost);
            }

        } catch (Exception e) {
            helper.log(getName(), "ERROR", "Error handling remove mitigation: " + e.getMessage());
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
        helper.log(getName(), "DEBUG",
                "Topology Discover Event Received. Data: " + (data != null ? data.toString() : "null"));

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

    /**
     * Handle Proactive Policy Installation (from startup workflows)
     */
    private void onInstallProactivePolicy(Event<?> event) {
        if (!running)
            return;

        try {
            Object data = event.getData();
            if (data == null) {
                helper.log(getName(), "WARN", "Proactive policy event has no data");
                return;
            }

            // Convert data to JSONObject for easier parsing
            JSONObject policyData;
            if (data instanceof JSONObject) {
                policyData = (JSONObject) data;
            } else if (data instanceof Map) {
                policyData = new JSONObject((Map<?, ?>) data);
            } else {
                helper.log(getName(), "WARN", "Unexpected policy data type: " + data.getClass().getName());
                return;
            }

            String policyName = policyData.optString("policy_name", "unknown");
            String policyType = policyData.optString("policy_type", "unknown");

            helper.log(getName(), "INFO", "Installing proactive policy: " + policyName + " (type: " + policyType + ")");

            // Route to appropriate policy installer based on type
            boolean success = false;
            switch (policyType) {
                case "microsegmentation":
                    success = installMicrosegmentationPolicy(policyData);
                    break;
                case "rate_limiting":
                    success = installRateLimitingPolicy(policyData);
                    break;
                case "port_security":
                    success = installPortSecurityPolicy(policyData);
                    break;
                default:
                    helper.log(getName(), "WARN", "Unknown policy type: " + policyType);
                    return;
            }

            if (success) {
                helper.log(getName(), "INFO", "✅ Successfully installed proactive policy: " + policyName);
            } else {
                helper.log(getName(), "ERROR", "❌ Failed to install proactive policy: " + policyName);
            }

        } catch (Exception e) {
            helper.log(getName(), "ERROR", "Error installing proactive policy: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Install microsegmentation policy (default-deny SMB with exceptions)
     */
    private boolean installMicrosegmentationPolicy(JSONObject policyData) {
        try {
            String protocol = policyData.optString("protocol", "tcp");
            String defaultAction = policyData.optString("default_action", "DENY");
            int priority = policyData.optInt("priority", 1000);

            // Get ports to block
            org.json.JSONArray portsArray = policyData.optJSONArray("ports");
            if (portsArray == null || portsArray.length() == 0) {
                helper.log(getName(), "WARN", "No ports specified for microsegmentation policy");
                return false;
            }

            // For now, install a simple block rule for the specified ports
            // In production, this would parse allow_rules and deny_rules from the policy
            for (int i = 0; i < portsArray.length(); i++) {
                int port = portsArray.getInt(i);
                String flowId = "proactive-block-port-" + port;

                helper.log(getName(), "INFO", "  Installing flow to block " + protocol.toUpperCase() + " port " + port);

                // Use OpenDaylightClient to install the flow
                // For now, we'll log the action (actual implementation would call ODL REST API)
                helper.log(getName(), "INFO",
                        "  [SIMULATED] Flow ID: " + flowId + ", Priority: " + priority + ", Action: DROP");
            }

            return true;

        } catch (Exception e) {
            helper.log(getName(), "ERROR", "Failed to install microsegmentation policy: " + e.getMessage());
            return false;
        }
    }

    /**
     * Install rate limiting policy (detect SMB scanning)
     */
    private boolean installRateLimitingPolicy(JSONObject policyData) {
        try {
            int maxConnections = policyData.optInt("max_new_connections", 5);
            int timeWindow = policyData.optInt("time_window_seconds", 60);
            String actionOnExceed = policyData.optString("action_on_exceed", "BLOCK_AND_ALERT");

            helper.log(getName(), "INFO",
                    "  Rate limit: " + maxConnections + " connections per " + timeWindow + " seconds");
            helper.log(getName(), "INFO", "  Action on exceed: " + actionOnExceed);

            // In production, this would configure ODL's rate limiting features
            // For now, log the configuration
            helper.log(getName(), "INFO", "  [SIMULATED] Rate limiting policy installed");

            return true;

        } catch (Exception e) {
            helper.log(getName(), "ERROR", "Failed to install rate limiting policy: " + e.getMessage());
            return false;
        }
    }

    /**
     * Install port security policy (block external SMB)
     */
    private boolean installPortSecurityPolicy(JSONObject policyData) {
        try {
            String direction = policyData.optString("direction", "both");
            int priority = policyData.optInt("priority", 950);

            org.json.JSONArray portsArray = policyData.optJSONArray("ports");
            if (portsArray == null || portsArray.length() == 0) {
                helper.log(getName(), "WARN", "No ports specified for port security policy");
                return false;
            }

            for (int i = 0; i < portsArray.length(); i++) {
                int port = portsArray.getInt(i);
                helper.log(getName(), "INFO",
                        "  Blocking external traffic on port " + port + " (direction: " + direction + ")");
            }

            helper.log(getName(), "INFO", "  [SIMULATED] Port security policy installed");

            return true;

        } catch (Exception e) {
            helper.log(getName(), "ERROR", "Failed to install port security policy: " + e.getMessage());
            return false;
        }
    }

    private void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);
            odlBaseUrl = props.getProperty("odl.base_url", odlBaseUrl);
            odlUsername = props.getProperty("odl.username", odlUsername);
            odlPassword = props.getProperty("odl.password", odlPassword);
                quarantineDefaultNode = props.getProperty("quarantine.default_node", quarantineDefaultNode);
                quarantineContainArp = Boolean.parseBoolean(
                    props.getProperty("quarantine.containment.arp.enabled", String.valueOf(quarantineContainArp)));
                quarantineContainDhcp = Boolean.parseBoolean(
                    props.getProperty("quarantine.containment.dhcp.enabled", String.valueOf(quarantineContainDhcp)));
            moduleId = props.getProperty("module.id", moduleId);
            moduleName = props.getProperty("module.name", moduleName);
        } catch (IOException e) {
            System.out.println("[OpenDaylightModule] Using default config");
        }
    }

    private JSONObject parseCommandPayload(MitigationCommandData command) {
        String raw = command.getAdditionalParameters();
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONObject();
        }

        try {
            return new JSONObject(raw);
        } catch (Exception ignored) {
            helper.log(getName(), "WARN", "Could not parse additional mitigation payload JSON");
            return new JSONObject();
        }
    }

    private long extractDurationMs(JSONObject payload) {
        if (payload.has("duration_ms")) {
            return payload.optLong("duration_ms", 0L);
        }

        JSONObject lifecycle = payload.optJSONObject("lifecycle");
        if (lifecycle != null && lifecycle.has("duration_ms")) {
            return lifecycle.optLong("duration_ms", 0L);
        }

        return 0L;
    }

    private OpenDaylightClient.IsolationPolicy parseIsolationPolicy(JSONObject payload) {
        JSONObject policyJson = payload.optJSONObject("isolation_policy");
        if (policyJson == null) {
            policyJson = payload.optJSONObject("policy");
        }
        if (policyJson == null) {
            policyJson = new JSONObject();
        }

        String policyMode = policyJson.optString("policy_mode", payload.optString("policy_mode", "strict"));
        String managementHost = policyJson.optString("management_host", payload.optString("management_host", ""));
        boolean containArp = policyJson.has("contain_arp")
                ? policyJson.optBoolean("contain_arp", quarantineContainArp)
                : payload.optBoolean("contain_arp", quarantineContainArp);
        boolean containDhcp = policyJson.has("contain_dhcp")
                ? policyJson.optBoolean("contain_dhcp", quarantineContainDhcp)
                : payload.optBoolean("contain_dhcp", quarantineContainDhcp);

        List<Integer> managementPorts = new ArrayList<>();
        Object portsObj = policyJson.has("management_ports") ? policyJson.get("management_ports")
                : payload.opt("management_ports");
        if (portsObj instanceof org.json.JSONArray) {
            org.json.JSONArray portsArray = (org.json.JSONArray) portsObj;
            for (int i = 0; i < portsArray.length(); i++) {
                int port = portsArray.optInt(i, -1);
                if (port > 0 && port <= 65535) {
                    managementPorts.add(port);
                }
            }
        } else if (portsObj instanceof String) {
            String[] parts = ((String) portsObj).split(",");
            for (String part : parts) {
                try {
                    int port = Integer.parseInt(part.trim());
                    if (port > 0 && port <= 65535) {
                        managementPorts.add(port);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return new OpenDaylightClient.IsolationPolicy(
                policyMode,
                managementHost,
                managementPorts,
                containArp,
                containDhcp);
    }

    private void registerMitigation(String mitigationId, String targetHost, String targetMac,
            OpenDaylightClient.IsolationPolicy policy, String owner, boolean autoExpire,
            long durationMs) {
        long now = Instant.now().toEpochMilli();
        long expiresAt = autoExpire && durationMs > 0 ? now + durationMs : 0L;
        ActiveMitigation mitigation = new ActiveMitigation(mitigationId, targetHost, targetMac, policy, owner, now,
                expiresAt);
        activeMitigations.put(mitigationId, mitigation);

        cancelMitigationTimer(mitigationId);
        if (autoExpire && durationMs > 0) {
            ScheduledFuture<?> timer = mitigationScheduler.schedule(() -> autoExpireMitigation(mitigationId),
                    durationMs, TimeUnit.MILLISECONDS);
            mitigationTimers.put(mitigationId, timer);
            helper.log(getName(), "INFO", "Scheduled auto-expiry for mitigation " + mitigationId + " in "
                    + durationMs + "ms");
        }
    }

    private void cancelMitigationTimer(String mitigationId) {
        ScheduledFuture<?> timer = mitigationTimers.remove(mitigationId);
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private void autoExpireMitigation(String mitigationId) {
        ActiveMitigation mitigation = activeMitigations.get(mitigationId);
        if (mitigation == null) {
            return;
        }

        helper.log(getName(), "INFO", "Auto-expiring mitigation " + mitigationId + " for host "
                + mitigation.targetHost);
        boolean removed = odlClient.removeIsolation(mitigation.targetHost, mitigation.targetMac, mitigationId,
            mitigation.policy);
        if (removed) {
            activeMitigations.remove(mitigationId);
            mitigationTimers.remove(mitigationId);
            helper.log(getName(), "INFO", "Auto-expired mitigation removed: " + mitigationId);
        } else {
            helper.log(getName(), "ERROR", "Failed to auto-expire mitigation: " + mitigationId);
        }
    }
}
