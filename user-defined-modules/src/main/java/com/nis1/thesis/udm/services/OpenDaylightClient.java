package com.nis1.thesis.udm.services;

import com.nis1.thesis.sdk.ModuleHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Service for interacting with OpenDaylight RESTCONF API.
 */
public class OpenDaylightClient {

    private final ModuleHelper helper;
    private final String moduleName;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String defaultNode;
    private final boolean defaultContainArp;
    private final boolean defaultContainDhcp;

    // Default SDN settings
    private static final int DEFAULT_TABLE = 0;
    private static final int DROP_PRIORITY = 2000;
    private static final int ALLOW_PRIORITY = 2200;

    public OpenDaylightClient(ModuleHelper helper, String moduleName, String baseUrl, String username,
            String password) {
        this(helper, moduleName, baseUrl, username, password, "openflow:1", false, false);
    }

    public OpenDaylightClient(ModuleHelper helper, String moduleName, String baseUrl, String username,
            String password, String defaultNode, boolean defaultContainArp, boolean defaultContainDhcp) {
        this.helper = helper;
        this.moduleName = moduleName;
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.defaultNode = defaultNode == null || defaultNode.isBlank() ? "openflow:1" : defaultNode;
        this.defaultContainArp = defaultContainArp;
        this.defaultContainDhcp = defaultContainDhcp;
    }

    public static class IsolationPolicy {
        public final String policyMode;
        public final String managementHostCidr;
        public final List<Integer> managementPorts;
        public final boolean containArp;
        public final boolean containDhcp;

        public IsolationPolicy(String policyMode, String managementHostCidr, List<Integer> managementPorts,
                boolean containArp, boolean containDhcp) {
            this.policyMode = policyMode == null || policyMode.isBlank() ? "strict" : policyMode;
            this.managementHostCidr = managementHostCidr == null ? "" : managementHostCidr.trim();
            this.managementPorts = managementPorts == null ? Collections.emptyList() : managementPorts;
            this.containArp = containArp;
            this.containDhcp = containDhcp;
        }
    }

    public static class EndpointLocalization {
        public final String status;
        public final String nodeId;
        public final String portId;
        public final String sourceOfTruth;
        public final String fallbackReason;

        public EndpointLocalization(String status, String nodeId, String portId, String sourceOfTruth,
                String fallbackReason) {
            this.status = status;
            this.nodeId = nodeId;
            this.portId = portId;
            this.sourceOfTruth = sourceOfTruth;
            this.fallbackReason = fallbackReason;
        }
    }

    public static class IsolationResult {
        public final boolean success;
        public final EndpointLocalization localization;
        public final int dropRules;
        public final int allowRules;

        public IsolationResult(boolean success, EndpointLocalization localization, int dropRules, int allowRules) {
            this.success = success;
            this.localization = localization;
            this.dropRules = dropRules;
            this.allowRules = allowRules;
        }
    }

    /**
     * Isolate a host by installing a high-priority DROP flow.
     *
     * @param targetIp The IP to block
     * @return true if successful
     */
    public boolean isolateHost(String targetIp) {
        return isolateHost(targetIp, null);
    }

    /**
     * Isolate a host using flow IDs that are tied to a mitigation record.
     */
    public boolean isolateHost(String targetIp, String mitigationId) {
        return isolateHost(targetIp, null, mitigationId, null).success;
    }

    public IsolationResult isolateHost(String targetIp, String targetMac, String mitigationId, IsolationPolicy policy) {
        if ((targetIp == null || targetIp.trim().isEmpty()) && (targetMac == null || targetMac.trim().isEmpty())) {
            helper.log(moduleName, "ERROR", "Cannot isolate empty target host");
            return new IsolationResult(false,
                    new EndpointLocalization("fallback", defaultNode, null, "default-node", "missing-target"), 0, 0);
        }

        IsolationPolicy effectivePolicy = effectivePolicy(policy);
        EndpointLocalization localization = resolveEndpointLocalization(targetIp, targetMac);
        String flowBase = buildFlowBase(targetIp, targetMac, mitigationId);

        int dropRules = 0;
        int allowRules = 0;
        boolean success = true;

        if (targetIp != null && !targetIp.isBlank()) {
            success &= putFlow(localization.nodeId, flowBase + "-ip-src-drop",
                    buildIpv4DropFlowJson(flowBase + "-ip-src-drop", targetIp, true));
            success &= putFlow(localization.nodeId, flowBase + "-ip-dst-drop",
                    buildIpv4DropFlowJson(flowBase + "-ip-dst-drop", targetIp, false));
            dropRules += 2;
        }

        if (targetMac != null && !targetMac.isBlank()) {
            success &= putFlow(localization.nodeId, flowBase + "-mac-src-drop",
                    buildMacDropFlowJson(flowBase + "-mac-src-drop", targetMac, true));
            success &= putFlow(localization.nodeId, flowBase + "-mac-dst-drop",
                    buildMacDropFlowJson(flowBase + "-mac-dst-drop", targetMac, false));
            dropRules += 2;
        }

        if (effectivePolicy.containArp && targetMac != null && !targetMac.isBlank()) {
            success &= putFlow(localization.nodeId, flowBase + "-arp-src-drop",
                    buildArpDropFlowJson(flowBase + "-arp-src-drop", targetMac, true));
            success &= putFlow(localization.nodeId, flowBase + "-arp-dst-drop",
                    buildArpDropFlowJson(flowBase + "-arp-dst-drop", targetMac, false));
            dropRules += 2;
        }

        if (effectivePolicy.containDhcp && targetIp != null && !targetIp.isBlank()) {
            success &= putFlow(localization.nodeId, flowBase + "-dhcp-src-drop",
                    buildDhcpDropFlowJson(flowBase + "-dhcp-src-drop", targetIp, true));
            success &= putFlow(localization.nodeId, flowBase + "-dhcp-dst-drop",
                    buildDhcpDropFlowJson(flowBase + "-dhcp-dst-drop", targetIp, false));
            dropRules += 2;
        }

        if (canInstallAllowlist(effectivePolicy, targetIp)) {
            for (Integer port : effectivePolicy.managementPorts) {
                String allowToTarget = flowBase + "-allow-mgr-in-" + port;
                String allowFromTarget = flowBase + "-allow-mgr-out-" + port;
                success &= putFlow(localization.nodeId, allowToTarget,
                        buildAllowMgmtFlowJson(allowToTarget, effectivePolicy.managementHostCidr, targetIp, port, true));
                success &= putFlow(localization.nodeId, allowFromTarget,
                        buildAllowMgmtFlowJson(allowFromTarget, effectivePolicy.managementHostCidr, targetIp, port,
                                false));
                allowRules += 2;
            }
        }

        helper.log(moduleName, "INFO",
                "Quarantine policy applied. target=" + (targetIp != null ? targetIp : targetMac) +
                        ", node=" + localization.nodeId +
                        ", status=" + localization.status +
                        ", drops=" + dropRules +
                        ", allowlist=" + allowRules);

        return new IsolationResult(success, localization, dropRules, allowRules);
    }

    private IsolationPolicy effectivePolicy(IsolationPolicy policy) {
        if (policy != null) {
            return policy;
        }

        return new IsolationPolicy("strict", "", Collections.emptyList(), defaultContainArp, defaultContainDhcp);
    }

    private boolean canInstallAllowlist(IsolationPolicy policy, String targetIp) {
        return targetIp != null && !targetIp.isBlank() &&
                policy.managementHostCidr != null && !policy.managementHostCidr.isBlank() &&
                policy.managementPorts != null && !policy.managementPorts.isEmpty();
    }

    private EndpointLocalization resolveEndpointLocalization(String targetIp, String targetMac) {
        try {
            String url = baseUrl + "/restconf/operational/network-topology:network-topology";
            String responseBody = sendRestRequestForBody("GET", url, null);
            if (responseBody == null || responseBody.isBlank()) {
                return new EndpointLocalization("fallback", defaultNode, null, "default-node", "empty-topology");
            }

            JSONObject topology = new JSONObject(responseBody);
            JSONArray topologies = topology.optJSONObject("network-topology") != null
                    ? topology.optJSONObject("network-topology").optJSONArray("topology")
                    : null;
            if (topologies == null) {
                return new EndpointLocalization("fallback", defaultNode, null, "default-node", "missing-topology");
            }

            String normalizedMac = targetMac == null ? "" : targetMac.toLowerCase();
            for (int i = 0; i < topologies.length(); i++) {
                JSONObject topo = topologies.optJSONObject(i);
                if (topo == null) {
                    continue;
                }
                JSONArray nodes = topo.optJSONArray("node");
                if (nodes == null) {
                    continue;
                }

                for (int j = 0; j < nodes.length(); j++) {
                    JSONObject node = nodes.optJSONObject(j);
                    if (node == null) {
                        continue;
                    }
                    String nodeId = node.optString("node-id", "");
                    if (!nodeId.startsWith("host:")) {
                        continue;
                    }

                    boolean macMatch = !normalizedMac.isBlank() &&
                            nodeId.replace("host:", "").equalsIgnoreCase(normalizedMac);
                    boolean ipMatch = false;
                    JSONArray addresses = node.optJSONArray("host-tracker-service:addresses");
                    if (addresses != null && targetIp != null && !targetIp.isBlank()) {
                        for (int k = 0; k < addresses.length(); k++) {
                            JSONObject address = addresses.optJSONObject(k);
                            if (address != null && targetIp.equals(address.optString("ip"))) {
                                ipMatch = true;
                                break;
                            }
                        }
                    }

                    if (!macMatch && !ipMatch) {
                        continue;
                    }

                    JSONArray attachmentPoints = node.optJSONArray("host-tracker-service:attachment-points");
                    if (attachmentPoints == null || attachmentPoints.length() == 0) {
                        return new EndpointLocalization("partial", defaultNode, null,
                                "odl-host-tracker", "missing-attachment-point");
                    }

                    JSONObject attachment = attachmentPoints.optJSONObject(0);
                    String tpId = attachment != null ? attachment.optString("tp-id", null) : null;
                    if (tpId == null || tpId.isBlank()) {
                        return new EndpointLocalization("partial", defaultNode, null,
                                "odl-host-tracker", "missing-tp-id");
                    }

                    String[] segments = tpId.split(":");
                    String resolvedNode = segments.length >= 2 ? segments[0] + ":" + segments[1] : defaultNode;
                    return new EndpointLocalization("resolved", resolvedNode, tpId, "odl-host-tracker", null);
                }
            }
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "Localization lookup failed, using default node: " + e.getMessage());
            return new EndpointLocalization("fallback", defaultNode, null, "default-node", "lookup-error");
        }

        return new EndpointLocalization("fallback", defaultNode, null, "default-node", "host-not-found");
    }

    /**
     * Remove isolation for a host.
     */
    public boolean removeIsolation(String targetIp) {
        return removeIsolation(targetIp, null);
    }

    /**
     * Remove isolation flows associated with the given mitigation id.
     */
    public boolean removeIsolation(String targetIp, String mitigationId) {
        return removeIsolation(targetIp, null, mitigationId, null);
    }

    public boolean removeIsolation(String targetIp, String targetMac, String mitigationId, IsolationPolicy policy) {
        if ((targetIp == null || targetIp.trim().isEmpty()) && (targetMac == null || targetMac.trim().isEmpty())) {
            helper.log(moduleName, "ERROR", "Cannot remove isolation for empty target host");
            return false;
        }

        IsolationPolicy effectivePolicy = effectivePolicy(policy);
        EndpointLocalization localization = resolveEndpointLocalization(targetIp, targetMac);
        String flowBase = buildFlowBase(targetIp, targetMac, mitigationId);

        List<String> flowIds = new ArrayList<>();
        flowIds.add(flowBase + "-ip-src-drop");
        flowIds.add(flowBase + "-ip-dst-drop");
        flowIds.add(flowBase + "-mac-src-drop");
        flowIds.add(flowBase + "-mac-dst-drop");
        flowIds.add(flowBase + "-arp-src-drop");
        flowIds.add(flowBase + "-arp-dst-drop");
        flowIds.add(flowBase + "-dhcp-src-drop");
        flowIds.add(flowBase + "-dhcp-dst-drop");

        if (effectivePolicy.managementPorts != null) {
            for (Integer port : effectivePolicy.managementPorts) {
                flowIds.add(flowBase + "-allow-mgr-in-" + port);
                flowIds.add(flowBase + "-allow-mgr-out-" + port);
            }
        }

        boolean ok = true;
        for (String flowId : flowIds) {
            ok &= deleteFlow(localization.nodeId, flowId);
        }

        return ok;
    }

    private boolean putFlow(String nodeId, String flowId, String jsonPayload) {
        String url = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d/flow/%s",
                baseUrl, nodeId, DEFAULT_TABLE, flowId);
        return sendRestRequest("PUT", url, jsonPayload);
    }

    private boolean deleteFlow(String nodeId, String flowId) {
        String url = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d/flow/%s",
                baseUrl, nodeId, DEFAULT_TABLE, flowId);
        return sendRestRequest("DELETE", url, null);
    }

    private String buildFlowBase(String targetIp, String targetMac, String mitigationId) {
        String idPart = mitigationId != null && !mitigationId.trim().isEmpty()
                ? sanitize(mitigationId)
                : "host-" + sanitize(targetIp != null && !targetIp.isBlank() ? targetIp : targetMac);
        return "soar-isolate-" + idPart;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private boolean sendRestRequest(String method, String urlStr, String jsonBody) {
        try {
            helper.log(moduleName, "DEBUG", "ODL Request: " + method + " " + urlStr);
            if (jsonBody != null) {
                helper.log(moduleName, "DEBUG", "Payload: " + jsonBody);
            }

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);

            // Auth
            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);

            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            if (jsonBody != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
            }

            int responseCode = conn.getResponseCode();
            helper.log(moduleName, "INFO", "ODL Response: " + responseCode + " for " + method + " " + urlStr);

            if (responseCode >= 400) {
                try (java.io.InputStream errorStream = conn.getErrorStream()) {
                    if (errorStream != null) {
                        String responseBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                        helper.log(moduleName, "ERROR", "ODL Error Body: " + responseBody);
                    }
                } catch (Exception ex) {
                    helper.log(moduleName, "ERROR", "Could not read error body: " + ex.getMessage());
                }
            }

            return responseCode >= 200 && responseCode < 300;

        } catch (Exception e) {
            helper.log(moduleName, "ERROR", "RESTCONF request failed: " + e.getMessage());
            e.printStackTrace(); // Ensure full stack trace is visible
            return false;
        }
    }

    private String sendRestRequestForBody(String method, String urlStr, String jsonBody) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);

            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            if (jsonBody != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }

            int responseCode = conn.getResponseCode();
            InputStream stream = responseCode >= 200 && responseCode < 300
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "REST body request failed: " + e.getMessage());
            return null;
        }
    }

    private String buildIpv4DropFlowJson(String flowId, String ipAddress, boolean sourceMatch) {
        String matchKey = sourceMatch ? "ipv4-source" : "ipv4-destination";
        return "{\n" +
                "  \"flow\": [\n" +
                "    {\n" +
                "      \"id\": \"" + flowId + "\",\n" +
                "      \"table_id\": " + DEFAULT_TABLE + ",\n" +
                "      \"priority\": " + DROP_PRIORITY + ",\n" +
                "      \"match\": {\n" +
                "        \"" + matchKey + "\": \"" + ipAddress + "/32\",\n" +
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
                "}";
    }

            private String buildMacDropFlowJson(String flowId, String macAddress, boolean sourceMatch) {
            String macKey = sourceMatch ? "source" : "destination";
            return "{\n" +
                "  \"flow\": [\n" +
                "    {\n" +
                "      \"id\": \"" + flowId + "\",\n" +
                "      \"table_id\": " + DEFAULT_TABLE + ",\n" +
                "      \"priority\": " + DROP_PRIORITY + ",\n" +
                "      \"match\": {\n" +
                "        \"ethernet-match\": {\n" +
                "          \"ethernet-" + macKey + "\": { \"address\": \"" + macAddress + "\" }\n" +
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
                "}";
            }

            private String buildArpDropFlowJson(String flowId, String macAddress, boolean sourceMatch) {
            String macKey = sourceMatch ? "source" : "destination";
            return "{\n" +
                "  \"flow\": [\n" +
                "    {\n" +
                "      \"id\": \"" + flowId + "\",\n" +
                "      \"table_id\": " + DEFAULT_TABLE + ",\n" +
                "      \"priority\": " + DROP_PRIORITY + ",\n" +
                "      \"match\": {\n" +
                "        \"ethernet-match\": {\n" +
                "          \"ethernet-type\": { \"type\": 2054 },\n" +
                "          \"ethernet-" + macKey + "\": { \"address\": \"" + macAddress + "\" }\n" +
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
                "}";
            }

            private String buildDhcpDropFlowJson(String flowId, String ipAddress, boolean sourceMatch) {
            String ipMatch = sourceMatch ? "ipv4-source" : "ipv4-destination";
            return "{\n" +
                "  \"flow\": [\n" +
                "    {\n" +
                "      \"id\": \"" + flowId + "\",\n" +
                "      \"table_id\": " + DEFAULT_TABLE + ",\n" +
                "      \"priority\": " + DROP_PRIORITY + ",\n" +
                "      \"match\": {\n" +
                "        \"" + ipMatch + "\": \"" + ipAddress + "/32\",\n" +
                "        \"ethernet-match\": { \"ethernet-type\": { \"type\": 2048 } },\n" +
                "        \"ip-match\": { \"ip-protocol\": 17 },\n" +
                "        \"udp-destination-port\": 67\n" +
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
                "}";
            }

            private String buildAllowMgmtFlowJson(String flowId, String mgmtHostCidr, String targetIp, int port,
                boolean managementToTarget) {
            String srcIp = managementToTarget ? mgmtHostCidr : targetIp + "/32";
            String dstIp = managementToTarget ? targetIp + "/32" : mgmtHostCidr;
            String portMatch = managementToTarget
                ? "\"tcp-destination-port\": " + port
                : "\"tcp-source-port\": " + port;

            return "{\n" +
                "  \"flow\": [\n" +
                "    {\n" +
                "      \"id\": \"" + flowId + "\",\n" +
                "      \"table_id\": " + DEFAULT_TABLE + ",\n" +
                "      \"priority\": " + ALLOW_PRIORITY + ",\n" +
                "      \"match\": {\n" +
                "        \"ipv4-source\": \"" + srcIp + "\",\n" +
                "        \"ipv4-destination\": \"" + dstIp + "\",\n" +
                "        \"ethernet-match\": { \"ethernet-type\": { \"type\": 2048 } },\n" +
                "        \"ip-match\": { \"ip-protocol\": 6 },\n" +
                "        " + portMatch + "\n" +
                "      },\n" +
                "      \"instructions\": {\n" +
                "        \"instruction\": [\n" +
                "          {\n" +
                "            \"order\": 0,\n" +
                "            \"apply-actions\": { \"action\": [] }\n" +
                "          }\n" +
                "        ]\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
            }
}
