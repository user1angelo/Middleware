package com.nis1.thesis.udm.services;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.nis1.thesis.sdk.ModuleHelper;

/**
 * Service for interacting with OpenDaylight RESTCONF API.
 */
public class OpenDaylightClient {

    public static class QuarantinePolicyOptions {
        public String mode = "strict_bi_directional";
        public boolean containArp = true;
        public boolean containDhcp = true;
    }

    private static class OwnedFlow {
        private final String nodeId;
        private final String flowId;

        private OwnedFlow(String nodeId, String flowId) {
            this.nodeId = nodeId;
            this.flowId = flowId;
        }
    }

    private static class MitigationRecord {
        private final String mitigationId;
        private final String targetIp;
        private final String targetMac;
        private final Set<OwnedFlow> installedFlows = new LinkedHashSet<>();

        private MitigationRecord(String mitigationId, String targetIp, String targetMac) {
            this.mitigationId = mitigationId;
            this.targetIp = targetIp;
            this.targetMac = targetMac;
        }
    }

    private final ModuleHelper helper;
    private final String moduleName;
    private final String baseUrl;
    private final String username;
    private final String password;

    // Default SDN settings
    private static final String DEFAULT_NODE = "openflow:1";
    private static final int DEFAULT_TABLE = 0;
    private static final int ISOLATION_PRIORITY = 1000;
    private static final String SYSTEM_FLOW_PREFIX = "sysq";
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    private final Map<String, MitigationRecord> ownedMitigations = new ConcurrentHashMap<>();
    private final Map<String, String> targetIndex = new ConcurrentHashMap<>();

    public OpenDaylightClient(ModuleHelper helper, String moduleName, String baseUrl, String username,
            String password) {
        this.helper = helper;
        this.moduleName = moduleName;
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    public boolean isolateHost(String targetIp, String targetMac, String mitigationId, QuarantinePolicyOptions options) {
        String normalizedIp = normalizeIp(targetIp);
        String normalizedMac = normalizeMac(targetMac);
        String normalizedMitigationId = mitigationId != null && !mitigationId.isBlank()
                ? mitigationId.trim()
                : "mit-" + System.currentTimeMillis();
        String targetKey = buildTargetKey(normalizedIp, normalizedMac);

        MitigationRecord existingById = ownedMitigations.get(normalizedMitigationId);
        if (existingById != null && !existingById.installedFlows.isEmpty()) {
            helper.log(moduleName, "INFO", "Duplicate mitigation event ignored (already active): "
                + normalizedMitigationId + " target=" + targetKey);
            return true;
        }

        String activeMitigationForTarget = targetIndex.get(targetKey);
        if (activeMitigationForTarget != null && !activeMitigationForTarget.equals(normalizedMitigationId)) {
            MitigationRecord activeRecord = ownedMitigations.get(activeMitigationForTarget);
            if (activeRecord != null && !activeRecord.installedFlows.isEmpty()) {
                helper.log(moduleName, "INFO", "Mitigation already active for target " + targetKey
                    + " (active=" + activeMitigationForTarget + ", incoming=" + normalizedMitigationId
                    + "); duplicate isolate request ignored");
                return true;
            }

            helper.log(moduleName, "WARN", "Target index was stale for " + targetKey
                + " (mitigation=" + activeMitigationForTarget + "); continuing with fresh mitigation "
                + normalizedMitigationId);
            targetIndex.remove(targetKey, activeMitigationForTarget);
        }

        QuarantinePolicyOptions effectiveOptions = options != null ? options : new QuarantinePolicyOptions();
        Set<String> candidateNodes = resolveCandidateNodesForTarget(normalizedIp, normalizedMac);
        MitigationRecord record = new MitigationRecord(normalizedMitigationId, normalizedIp, normalizedMac);

        Set<String> flowTokens = buildFlowTokens(normalizedIp, normalizedMac, effectiveOptions);
        if (flowTokens.isEmpty()) {
            helper.log(moduleName, "ERROR", "Cannot isolate host: neither valid IP nor MAC selector is available");
            return false;
        }

        int installedCount = 0;
        for (String nodeId : candidateNodes) {
            for (String token : flowTokens) {
                String flowId = buildSystemFlowId(normalizedMitigationId, token);
                String payload = buildIsolationFlowJson(flowId, normalizedIp, normalizedMac, token);
                int responseCode = sendFlowRequest("PUT", nodeId, flowId, payload);
                if (responseCode >= 200 && responseCode < 300) {
                    installedCount++;
                    record.installedFlows.add(new OwnedFlow(nodeId, flowId));
                }
            }
        }

        if (record.installedFlows.isEmpty()) {
            helper.log(moduleName, "ERROR", "Failed to install quarantine drop rules for mitigation " + normalizedMitigationId);
            return false;
        }

        ownedMitigations.put(normalizedMitigationId, record);
        targetIndex.put(targetKey, normalizedMitigationId);

        helper.log(moduleName, "INFO", "Quarantine drop rules applied: " + installedCount
                + " (mitigation_id=" + normalizedMitigationId + ", mode=" + effectiveOptions.mode
                + ", arp=" + effectiveOptions.containArp + ", dhcp=" + effectiveOptions.containDhcp + ")");
        return true;
    }

    public boolean removeIsolation(String targetIp, String targetMac, String mitigationId) {
        String normalizedIp = normalizeIp(targetIp);
        String normalizedMac = normalizeMac(targetMac);
        String resolvedMitigationId = mitigationId != null && !mitigationId.isBlank()
                ? mitigationId.trim()
                : targetIndex.get(buildTargetKey(normalizedIp, normalizedMac));

        if (resolvedMitigationId == null) {
            helper.log(moduleName, "WARN", "No owned mitigation record found for target " + buildTargetKey(normalizedIp, normalizedMac));
            return false;
        }

        MitigationRecord record = ownedMitigations.get(resolvedMitigationId);
        if (record == null || record.installedFlows.isEmpty()) {
            helper.log(moduleName, "WARN", "No in-memory flow records found for mitigation " + resolvedMitigationId
                    + "; falling back to prefix-based cleanup");
            boolean fallbackRemoved = removeOwnedFlowsByMitigationPrefix(resolvedMitigationId);
            if (fallbackRemoved) {
                targetIndex.remove(buildTargetKey(normalizedIp, normalizedMac));
                ownedMitigations.remove(resolvedMitigationId);
            }
            return fallbackRemoved;
        }

        boolean allRemovedOrAbsent = true;
        for (OwnedFlow flow : record.installedFlows) {
            int responseCode = sendFlowRequest("DELETE", flow.nodeId, flow.flowId, null);
            if (!((responseCode >= 200 && responseCode < 300) || responseCode == 404)) {
                allRemovedOrAbsent = false;
            }
        }

        if (allRemovedOrAbsent) {
            ownedMitigations.remove(resolvedMitigationId);
            targetIndex.remove(buildTargetKey(record.targetIp, record.targetMac));
            helper.log(moduleName, "INFO", "Removed system-owned quarantine rules for mitigation " + resolvedMitigationId);
        } else {
            helper.log(moduleName, "WARN", "Some tracked flow deletions failed for mitigation " + resolvedMitigationId
                    + "; attempting prefix-based cleanup fallback");
            boolean fallbackRemoved = removeOwnedFlowsByMitigationPrefix(resolvedMitigationId);
            if (fallbackRemoved) {
                ownedMitigations.remove(resolvedMitigationId);
                targetIndex.remove(buildTargetKey(record.targetIp, record.targetMac));
                helper.log(moduleName, "INFO", "Fallback cleanup completed for mitigation " + resolvedMitigationId);
                return true;
            }
            helper.log(moduleName, "ERROR", "Failed to remove some system-owned quarantine rules for mitigation " + resolvedMitigationId);
        }

        return allRemovedOrAbsent;
    }

    private boolean removeOwnedFlowsByMitigationPrefix(String mitigationId) {
        String mitigationPrefix = SYSTEM_FLOW_PREFIX + "_" + sanitize(mitigationId) + "_";
        Set<String> nodes = fetchAllOpenFlowNodes();
        if (nodes.isEmpty()) {
            nodes.add(DEFAULT_NODE);
        }

        int deletionFailures = 0;
        int matchedFlows = 0;

        for (String nodeId : nodes) {
            Set<String> flowIds = fetchConfiguredFlowIdsForNode(nodeId);
            for (String flowId : flowIds) {
                if (!flowId.startsWith(mitigationPrefix)) {
                    continue;
                }

                matchedFlows++;
                int responseCode = sendFlowRequest("DELETE", nodeId, flowId, null);
                if (!((responseCode >= 200 && responseCode < 300) || responseCode == 404)) {
                    deletionFailures++;
                }
            }
        }

        if (matchedFlows == 0) {
            helper.log(moduleName, "INFO", "No configured flows matched mitigation prefix " + mitigationPrefix
                    + " (already removed or never installed)");
            return true;
        }

        if (deletionFailures > 0) {
            helper.log(moduleName, "ERROR", "Prefix cleanup failed for " + deletionFailures + " flow(s) under " + mitigationPrefix);
            return false;
        }

        helper.log(moduleName, "INFO", "Prefix cleanup removed " + matchedFlows + " flow(s) under " + mitigationPrefix);
        return true;
    }

    private Set<String> fetchConfiguredFlowIdsForNode(String nodeId) {
        Set<String> flowIds = new LinkedHashSet<>();
        String tableUrl = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d", baseUrl, nodeId,
                DEFAULT_TABLE);

        try {
            JSONObject response = fetchJson(tableUrl);
            if (response == null) {
                return flowIds;
            }

            JSONArray tables = response.optJSONArray("table");
            if (tables == null) {
                tables = response.optJSONArray("flow-node-inventory:table");
            }

            if (tables != null) {
                for (int i = 0; i < tables.length(); i++) {
                    JSONObject table = tables.optJSONObject(i);
                    if (table == null) {
                        continue;
                    }

                    JSONArray flows = table.optJSONArray("flow");
                    if (flows == null) {
                        flows = table.optJSONArray("flow-node-inventory:flow");
                    }

                    if (flows == null) {
                        continue;
                    }

                    for (int j = 0; j < flows.length(); j++) {
                        JSONObject flow = flows.optJSONObject(j);
                        if (flow == null) {
                            continue;
                        }
                        String id = flow.optString("id", "");
                        if (!id.isBlank()) {
                            flowIds.add(id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "Failed to fetch configured flows for node " + nodeId + ": " + e.getMessage());
        }

        return flowIds;
    }

    private JSONObject fetchJson(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return null;
            }

            try (InputStream input = conn.getInputStream()) {
                String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                return new JSONObject(body);
            }
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "GET JSON request failed for " + urlStr + ": " + e.getMessage());
            return null;
        }
    }

    private String normalizeIp(String ip) {
        if (ip == null) {
            return null;
        }
        String value = ip.trim();
        if (value.isEmpty() || value.contains("[MISSING:")) {
            return null;
        }

        Matcher matcher = IPV4_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }

        return matcher.group();
    }

    private String normalizeMac(String mac) {
        if (mac == null) {
            return null;
        }
        String value = mac.trim().toLowerCase();
        return value.isEmpty() ? null : value;
    }

    private String sanitize(String input) {
        if (input == null) {
            return "na";
        }
        return input.replaceAll("[^a-zA-Z0-9]", "_");
    }

    private String buildSystemFlowId(String mitigationId, String token) {
        return SYSTEM_FLOW_PREFIX + "_" + sanitize(mitigationId) + "_" + sanitize(token);
    }

    private String buildTargetKey(String ip, String mac) {
        String ipPart = ip != null ? ip : "no-ip";
        String macPart = mac != null ? mac : "no-mac";
        return ipPart + "|" + macPart;
    }

    private int sendFlowRequest(String method, String nodeId, String flowId, String jsonBody) {
        String url = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d/flow/%s",
                baseUrl, nodeId, DEFAULT_TABLE, flowId);
        return sendRestRequest(method, url, jsonBody);
    }

    private Set<String> buildFlowTokens(String targetIp, String targetMac, QuarantinePolicyOptions options) {
        Set<String> tokens = new LinkedHashSet<>();
        boolean useIp = shouldUseIpSelector(options.mode, targetIp != null);
        boolean useMac = shouldUseMacSelector(options.mode, targetMac != null);

        if (useIp && targetIp != null) {
            tokens.add("ipv4_src");
            tokens.add("ipv4_dst");

            if (options.containArp) {
                tokens.add("arp_spa");
                tokens.add("arp_tpa");
            }

            if (options.containDhcp) {
                tokens.add("dhcp_src67dst68");
                tokens.add("dhcp_src68dst67");
            }
        }

        if (useMac && targetMac != null) {
            tokens.add("eth_src");
            tokens.add("eth_dst");

            if (options.containArp) {
                tokens.add("arp_eth_src");
                tokens.add("arp_eth_dst");
            }

            if (options.containDhcp) {
                tokens.add("dhcp_eth_src");
                tokens.add("dhcp_eth_dst");
            }
        }

        return tokens;
    }

    private boolean shouldUseIpSelector(String mode, boolean hasIp) {
        if (!hasIp) {
            return false;
        }
        String normalized = mode != null ? mode.toLowerCase() : "strict_bi_directional";
        return !"mac_only_bidirectional".equals(normalized);
    }

    private boolean shouldUseMacSelector(String mode, boolean hasMac) {
        if (!hasMac) {
            return false;
        }
        String normalized = mode != null ? mode.toLowerCase() : "strict_bi_directional";
        return !"ip_only_bidirectional".equals(normalized);
    }

    private int sendRestRequest(String method, String urlStr, String jsonBody) {
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

            return responseCode;

        } catch (Exception e) {
            helper.log(moduleName, "ERROR", "RESTCONF request failed: " + e.getMessage());
            e.printStackTrace(); // Ensure full stack trace is visible
            return -1;
        }
    }

    private Set<String> resolveCandidateNodesForTarget(String targetIp, String targetMac) {
        Set<String> nodes = new LinkedHashSet<>();

        try {
            JSONObject topology = fetchTopology();
            if (topology != null) {
                JSONObject networkTopology = topology.optJSONObject("network-topology");
                JSONArray topologies = networkTopology != null ? networkTopology.optJSONArray("topology") : null;

                if (topologies != null) {
                    for (int i = 0; i < topologies.length(); i++) {
                        JSONObject topo = topologies.optJSONObject(i);
                        if (topo == null) {
                            continue;
                        }

                        JSONArray nodeArray = topo.optJSONArray("node");
                        if (nodeArray == null) {
                            continue;
                        }

                        for (int j = 0; j < nodeArray.length(); j++) {
                            JSONObject node = nodeArray.optJSONObject(j);
                            if (node == null) {
                                continue;
                            }

                            String nodeId = node.optString("node-id", "");
                            if (!nodeId.startsWith("host:")) {
                                continue;
                            }

                            JSONArray addresses = node.optJSONArray("host-tracker-service:addresses");
                            String hostMac = nodeId.replace("host:", "").toLowerCase();
                            boolean ipMatch = hostMatchesIp(addresses, targetIp);
                            boolean macMatch = hostMatchesMac(hostMac, targetMac);
                            if (!ipMatch && !macMatch) {
                                continue;
                            }

                            JSONArray attachmentPoints = node.optJSONArray("host-tracker-service:attachment-points");
                            String attachedNode = extractNodeFromAttachmentPoints(attachmentPoints);
                            if (attachedNode != null) {
                                nodes.add(attachedNode);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "Failed to resolve host attachment from topology: " + e.getMessage());
        }

        if (nodes.isEmpty()) {
            nodes.addAll(fetchAllOpenFlowNodes());
        }

        if (nodes.isEmpty()) {
            nodes.add(DEFAULT_NODE);
        }

        helper.log(moduleName, "DEBUG", "Candidate ODL nodes for target " + buildTargetKey(targetIp, targetMac) + ": " + nodes);
        return nodes;
    }

    private Set<String> fetchAllOpenFlowNodes() {
        Set<String> nodes = new LinkedHashSet<>();

        try {
            JSONObject topology = fetchTopology();
            if (topology == null) {
                return nodes;
            }

            JSONObject networkTopology = topology.optJSONObject("network-topology");
            JSONArray topologies = networkTopology != null ? networkTopology.optJSONArray("topology") : null;
            if (topologies == null) {
                return nodes;
            }

            for (int i = 0; i < topologies.length(); i++) {
                JSONObject topo = topologies.optJSONObject(i);
                if (topo == null) {
                    continue;
                }

                JSONArray nodeArray = topo.optJSONArray("node");
                if (nodeArray == null) {
                    continue;
                }

                for (int j = 0; j < nodeArray.length(); j++) {
                    JSONObject node = nodeArray.optJSONObject(j);
                    if (node == null) {
                        continue;
                    }

                    String nodeId = node.optString("node-id", "");
                    if (nodeId.startsWith("openflow:")) {
                        nodes.add(nodeId);
                    }
                }
            }
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "Failed to discover OpenFlow nodes: " + e.getMessage());
        }

        return nodes;
    }

    private JSONObject fetchTopology() {
        String topologyUrl = String.format("%s/restconf/operational/network-topology:network-topology", baseUrl);
        try {
            URL url = new URL(topologyUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                helper.log(moduleName, "WARN", "Topology fetch failed with response code " + responseCode);
                return null;
            }

            try (InputStream input = conn.getInputStream()) {
                String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                return new JSONObject(body);
            }
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "Topology fetch error: " + e.getMessage());
            return null;
        }
    }

    private boolean hostMatchesIp(JSONArray addresses, String targetIp) {
        if (addresses == null || targetIp == null || targetIp.isBlank()) {
            return false;
        }

        for (int i = 0; i < addresses.length(); i++) {
            JSONObject address = addresses.optJSONObject(i);
            if (address == null) {
                continue;
            }

            String ip = address.optString("ip", "");
            if (targetIp.equals(ip)) {
                return true;
            }
        }

        return false;
    }

    private boolean hostMatchesMac(String hostMac, String targetMac) {
        if (targetMac == null || targetMac.isBlank()) {
            return false;
        }
        return targetMac.equalsIgnoreCase(hostMac);
    }

    private String extractNodeFromAttachmentPoints(JSONArray attachmentPoints) {
        if (attachmentPoints == null) {
            return null;
        }

        for (int i = 0; i < attachmentPoints.length(); i++) {
            JSONObject ap = attachmentPoints.optJSONObject(i);
            if (ap == null) {
                continue;
            }

            String tpId = ap.optString("tp-id", "");
            if (!tpId.startsWith("openflow:")) {
                continue;
            }

            int lastColon = tpId.lastIndexOf(':');
            if (lastColon > 0) {
                return tpId.substring(0, lastColon);
            }

            return tpId;
        }

        return null;
    }

    private String buildIsolationFlowJson(String flowId, String ipAddress, String macAddress, String token) {
        JSONObject flow = new JSONObject();
        flow.put("id", flowId);
        flow.put("table_id", DEFAULT_TABLE);
        flow.put("priority", ISOLATION_PRIORITY);
        flow.put("match", buildMatch(ipAddress, macAddress, token));
        flow.put("instructions", buildDropInstruction());

        JSONArray flows = new JSONArray();
        flows.put(flow);

        JSONObject payload = new JSONObject();
        payload.put("flow", flows);
        return payload.toString();
    }

    private JSONObject buildMatch(String ipAddress, String macAddress, String token) {
        JSONObject match = new JSONObject();

        switch (token) {
            case "ipv4_src":
                match.put("ipv4-source", ipAddress + "/32");
                match.put("ethernet-match", ethernetType(2048));
                break;
            case "ipv4_dst":
                match.put("ipv4-destination", ipAddress + "/32");
                match.put("ethernet-match", ethernetType(2048));
                break;
            case "eth_src":
                match.put("ethernet-match", ethernetWithAddress("ethernet-source", macAddress, 2048));
                break;
            case "eth_dst":
                match.put("ethernet-match", ethernetWithAddress("ethernet-destination", macAddress, 2048));
                break;
            case "arp_spa":
                match.put("arp-source-transport-address", ipAddress + "/32");
                match.put("ethernet-match", ethernetType(2054));
                break;
            case "arp_tpa":
                match.put("arp-target-transport-address", ipAddress + "/32");
                match.put("ethernet-match", ethernetType(2054));
                break;
            case "arp_eth_src":
                match.put("ethernet-match", ethernetWithAddress("ethernet-source", macAddress, 2054));
                break;
            case "arp_eth_dst":
                match.put("ethernet-match", ethernetWithAddress("ethernet-destination", macAddress, 2054));
                break;
            case "dhcp_src67dst68":
                match.put("ipv4-source", ipAddress + "/32");
                match.put("ethernet-match", ethernetType(2048));
                match.put("ip-match", new JSONObject().put("ip-protocol", 17));
                match.put("udp-source-port", 67);
                match.put("udp-destination-port", 68);
                break;
            case "dhcp_src68dst67":
                match.put("ipv4-destination", ipAddress + "/32");
                match.put("ethernet-match", ethernetType(2048));
                match.put("ip-match", new JSONObject().put("ip-protocol", 17));
                match.put("udp-source-port", 68);
                match.put("udp-destination-port", 67);
                break;
            case "dhcp_eth_src":
                match.put("ethernet-match", ethernetWithAddress("ethernet-source", macAddress, 2048));
                match.put("ip-match", new JSONObject().put("ip-protocol", 17));
                match.put("udp-source-port", 67);
                match.put("udp-destination-port", 68);
                break;
            case "dhcp_eth_dst":
                match.put("ethernet-match", ethernetWithAddress("ethernet-destination", macAddress, 2048));
                match.put("ip-match", new JSONObject().put("ip-protocol", 17));
                match.put("udp-source-port", 68);
                match.put("udp-destination-port", 67);
                break;
            default:
                throw new IllegalArgumentException("Unknown quarantine token: " + token);
        }

        return match;
    }

    private JSONObject buildDropInstruction() {
        JSONObject dropAction = new JSONObject();
        dropAction.put("order", 0);
        dropAction.put("drop-action", new JSONObject());

        JSONArray actions = new JSONArray();
        actions.put(dropAction);

        JSONObject applyActions = new JSONObject();
        applyActions.put("action", actions);

        JSONObject instruction = new JSONObject();
        instruction.put("order", 0);
        instruction.put("apply-actions", applyActions);

        JSONArray instructionArray = new JSONArray();
        instructionArray.put(instruction);

        JSONObject instructions = new JSONObject();
        instructions.put("instruction", instructionArray);
        return instructions;
    }

    private JSONObject ethernetType(int type) {
        JSONObject ethernetType = new JSONObject();
        ethernetType.put("type", type);

        JSONObject ethernetMatch = new JSONObject();
        ethernetMatch.put("ethernet-type", ethernetType);
        return ethernetMatch;
    }

    private JSONObject ethernetWithAddress(String field, String macAddress, int type) {
        JSONObject ethernetMatch = ethernetType(type);

        JSONObject address = new JSONObject();
        address.put("address", macAddress);
        ethernetMatch.put(field, address);
        return ethernetMatch;
    }
}
