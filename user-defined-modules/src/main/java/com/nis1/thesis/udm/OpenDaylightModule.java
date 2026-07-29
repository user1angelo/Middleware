package com.nis1.thesis.udm;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import org.json.JSONArray;
import org.json.JSONObject;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.MitigationAction;
import com.nis1.thesis.sdk.MitigationCommandData;
import com.nis1.thesis.sdk.MitigationParameters;
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

    // Module identity
    private String moduleId = "odl_sdn_01";
    private String moduleName = "OpenDaylight SDN Module";

    // Config
    private String odlBaseUrl = "http://localhost:8181";
    private String odlUsername = "admin";
    private String odlPassword = "admin";
    private String defaultPolicyMode = "strict_bi_directional";
    private boolean defaultContainArp = true;
    private boolean defaultContainDhcp = true;
    private boolean suppressByIdOnly = true;

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
        this.odlClient = new OpenDaylightClient(helper, getName(), odlBaseUrl, odlUsername, odlPassword, suppressByIdOnly);

        this.running = true;

        // Restore mitigation state from previous session so flows installed
        // before a restart remain tracked and can be properly removed.
        odlClient.loadPersistedState();

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
            MitigationParameters metadata = command.getAdditionalParameters();
            if (metadata == null) {
                metadata = new MitigationParameters();
            }
            String targetMac = normalizeMac(metadata.getMacAddress());
            String mitigationId = normalizeBlank(metadata.getMitigationId());
            OpenDaylightClient.QuarantinePolicyOptions policyOptions = buildPolicyOptions(metadata.getQuarantinePolicy());

            helper.log(getName(), "INFO", "Received mitigation request: " + action + " for " + targetHost);
            helper.log(getName(), "DEBUG", "Full command data: " + command.toString()); // Assuming toString() is
                                                                                        // useful, otherwise we trust
                                                                                        // the fields

            boolean success = false;
            long appliedTimeMillis = System.currentTimeMillis();

            String justification = command.getJustification();
            String severity = (metadata.getSeverity() != null ? metadata.getSeverity() : "medium").toLowerCase();
            
            if (justification != null && (justification.contains("ARP") || justification.contains("arp") || justification.contains("spoof"))) {
                helper.log(getName(), "INFO", "ARP Spoofing mitigation triggered. Checking operational inventory for conflicting IP-MAC bindings...");
                java.util.Map<String, java.util.List<String>> conflicts = odlClient.checkConflictingIpMacBindings();
                for (java.util.Map.Entry<String, java.util.List<String>> entry : conflicts.entrySet()) {
                    if (entry.getValue().size() > 1) {
                        helper.log(getName(), "WARN", "⚠️ CONFLICTING IP-MAC BINDING DETECTED: IP " + entry.getKey() + " is bound to MACs: " + entry.getValue());
                    }
                }
            }
            
            if (justification != null && (justification.contains("Reconnaissance") || justification.contains("recon") || justification.contains("scan"))) {
                helper.log(getName(), "INFO", "Reconnaissance mitigation triggered. Severity: " + severity);
                if (severity.equals("critical") || severity.equals("high")) {
                    helper.log(getName(), "INFO", "High severity recon detected. Isolating host: " + targetHost);
                    action = MitigationAction.ISOLATE_VLAN;
                } else {
                    helper.log(getName(), "INFO", "Medium/Low severity recon detected. Applying targeted SDN blocking for host: " + targetHost);
                    action = MitigationAction.BLOCK_IP;
                }
            }

            if (action == MitigationAction.BLOCK_IP ||
                    action == MitigationAction.QUARANTINE ||
                    action == MitigationAction.ISOLATE_VLAN) {

                success = odlClient.isolateHost(targetHost, targetMac, mitigationId, policyOptions);
                appliedTimeMillis = System.currentTimeMillis(); // rule applied time
                if (success) {
                    helper.log(getName(), "INFO", "Successfully isolated host: " + targetHost
                            + " [mode=" + policyOptions.mode
                            + ", arp=" + policyOptions.containArp
                            + ", dhcp=" + policyOptions.containDhcp + "]");
                } else {
                    helper.log(getName(), "ERROR", "Failed to isolate host: " + targetHost);
                }
            } else {
                helper.log(getName(), "WARN", "Action " + action + " not supported by ODL module yet.");
            }

            logTelemetryBenchmark(metadata.getTelemetry(), appliedTimeMillis, "SDN Isolation Rules Applied");

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
            String targetHost = command.getTargetHost();
            MitigationParameters metadata = command.getAdditionalParameters();
            if (metadata == null) {
                metadata = new MitigationParameters();
            }
            String targetMac = normalizeMac(metadata.getMacAddress());
            String mitigationId = normalizeBlank(metadata.getMitigationId());
            String rollbackReason = normalizeBlank(metadata.getRollbackReason());

            helper.log(getName(), "INFO", "Received remove mitigation request for " + targetHost);
            if (rollbackReason != null) {
                helper.log(getName(), "INFO", "Rollback reason: " + rollbackReason);
            }

            OpenDaylightClient.RemoveIsolationResult result = odlClient.removeIsolationDetailed(targetHost, targetMac, mitigationId);
            if (result.success) {
                if (result.deletedHttp2xx > 0) {
                    helper.log(getName(), "INFO", "Successfully removed isolation from host: " + targetHost);
                } else {
                    helper.log(getName(), "WARN", "REMOVE_MITIGATION for mitigation " + result.resolvedMitigationId
                            + " target " + targetHost
                            + ": no flows were found or deleted — isolation may not have been active");
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

        // Write results to well-known file for backend consumption
        try {
            JSONObject scanResult = new JSONObject();
            JSONArray hosts = new JSONArray();
            for (Map.Entry<String, String> entry : results.entrySet()) {
                JSONObject host = new JSONObject();
                host.put("ip", entry.getKey());
                host.put("mac", entry.getValue());
                hosts.put(host);
            }
            scanResult.put("hosts", hosts);
            scanResult.put("timestamp", Instant.now().toString());
            scanResult.put("count", results.size());

            Files.writeString(Paths.get("/tmp/middleware_scan_results.json"), scanResult.toString());
            helper.log(getName(), "INFO", "Scan results written to /tmp/middleware_scan_results.json");
        } catch (IOException e) {
            helper.log(getName(), "ERROR", "Failed to write scan results file: " + e.getMessage());
        }

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
        boolean success = odlClient.isolateHost(ipAddress, null, null, buildPolicyOptions(null));
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
        OpenDaylightClient.RemoveIsolationResult result = odlClient.removeIsolationDetailed(ipAddress, null, null);
        if (result.success) {
            if (result.deletedHttp2xx > 0) {
                helper.log(getName(), "INFO", "Isolation removed for: " + ipAddress);
            } else {
                helper.log(getName(), "WARN", "REMOVE_MITIGATION for mitigation " + result.resolvedMitigationId
                        + " target " + ipAddress
                        + ": no flows were found or deleted — isolation may not have been active");
            }
        } else {
            helper.log(getName(), "ERROR", "Failed to remove isolation for: " + ipAddress);
        }
        return result.success;
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
                case "protocol_drop":
                    success = installProtocolDropPolicy(policyData);
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
            Map<String, Object> policyTelemetry = policyData.has("telemetry")
                    ? policyData.getJSONObject("telemetry").toMap()
                    : null;
            logTelemetryBenchmark(policyTelemetry, System.currentTimeMillis(), "SDN Policy Rules Applied");

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

    private boolean installProtocolDropPolicy(JSONObject policyData) {
        try {
            String protocol = policyData.optString("protocol", "").trim().toLowerCase();
            String sourceIp = policyData.optString("source_ip", "").trim();
            String policyName = policyData.optString("policy_name", "protocol_drop");

            if (protocol.isEmpty() || sourceIp.isEmpty()) {
                helper.log(getName(), "WARN", "protocol_drop policy missing protocol or source_ip");
                return false;
            }

            int ipProtocol;
            if ("icmp".equals(protocol)) {
                ipProtocol = 1;
            } else if ("tcp".equals(protocol)) {
                ipProtocol = 6;
            } else if ("udp".equals(protocol)) {
                ipProtocol = 17;
            } else {
                helper.log(getName(), "WARN", "Unsupported protocol for protocol_drop: " + protocol);
                return false;
            }

            String mitigationId = "policy-" + policyName + "-" + sourceIp + "-" + ipProtocol;
            boolean success = odlClient.applyProtocolDrop(sourceIp, ipProtocol, mitigationId);
            if (success) {
                helper.log(getName(), "INFO", "Installed protocol drop: protocol=" + protocol + " ip=" + sourceIp);
            }
            return success;
        } catch (Exception e) {
            helper.log(getName(), "ERROR", "Failed to install protocol_drop policy: " + e.getMessage());
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
            moduleId = props.getProperty("module.id", moduleId);
            moduleName = props.getProperty("module.name", moduleName);
            defaultPolicyMode = props.getProperty("mitigation.policy.mode", defaultPolicyMode);
            defaultContainArp = Boolean.parseBoolean(props.getProperty("mitigation.containment.arp", String.valueOf(defaultContainArp)));
            defaultContainDhcp = Boolean.parseBoolean(props.getProperty("mitigation.containment.dhcp", String.valueOf(defaultContainDhcp)));
            suppressByIdOnly = Boolean.parseBoolean(props.getProperty("odl.mitigation.suppress_by_id_only", String.valueOf(suppressByIdOnly)));
        } catch (IOException e) {
            System.out.println("[OpenDaylightModule] Using default config");
        }
    }

    private OpenDaylightClient.QuarantinePolicyOptions buildPolicyOptions(MitigationParameters.QuarantinePolicy policy) {
        OpenDaylightClient.QuarantinePolicyOptions options = new OpenDaylightClient.QuarantinePolicyOptions();
        options.mode = defaultPolicyMode;
        options.containArp = defaultContainArp;
        options.containDhcp = defaultContainDhcp;

        if (policy != null) {
            String requestedMode = normalizeBlank(policy.getMode());
            if (requestedMode != null) {
                options.mode = requestedMode;
            }
            if (policy.getContainArp() != null) {
                options.containArp = policy.getContainArp();
            }
            if (policy.getContainDhcp() != null) {
                options.containDhcp = policy.getContainDhcp();
            }
        }

        return options;
    }

    private void logTelemetryBenchmark(Map<String, Object> telemetry, long appliedTimeMillis, String appliedLabel) {
        if (telemetry == null || telemetry.isEmpty()) {
            return;
        }

        try {
            long alertTimeMs = toLong(telemetry.get("alert_generated_time_ms"));
            long wfTimeMs = toLong(telemetry.get("workflow_execution_time_ms"));

            double alertToRec = toDouble(telemetry.get("alert_to_received_delay_sec"));
            double recToWf = toDouble(telemetry.get("received_to_workflow_delay_sec"));
            double wfToApplied = wfTimeMs > 0 ? (appliedTimeMillis - wfTimeMs) / 1000.0 : 0.0;
            double totalDuration = alertTimeMs > 0 ? (appliedTimeMillis - alertTimeMs) / 1000.0 : 0.0;

            String analysisLog = String.format(
                "\n================ THESIS PERFORMANCE BENCHMARK ================\n" +
                "1. Suricata Alert Generation Time: %s\n" +
                "2. System Received Alert Time:     %s (Delay from alert: %.3f sec)\n" +
                "3. Workflow & ODL API Triggered:   %s (Delay from receipt: %.3f sec)\n" +
                "4. %s:    %s (Delay from API call: %.3f sec)\n" +
                "5. Total Containment Pipeline Duration: %.3f seconds\n" +
                "==============================================================",
                telemetry.getOrDefault("alert_generated_time", "N/A"),
                telemetry.getOrDefault("system_received_time", "N/A"), alertToRec,
                telemetry.getOrDefault("workflow_execution_time", "N/A"), recToWf,
                appliedLabel, Instant.ofEpochMilli(appliedTimeMillis).toString(), wfToApplied,
                totalDuration
            );
            helper.log(getName(), "INFO", analysisLog);
            System.out.println(analysisLog);
        } catch (Exception ex) {
            helper.log(getName(), "WARN", "Failed to parse telemetry benchmark data: " + ex.getMessage());
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    private String normalizeMac(String mac) {
        String value = normalizeBlank(mac);
        return value == null ? null : value.toLowerCase();
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
