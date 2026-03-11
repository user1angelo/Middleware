package com.nis1.thesis.udm.services;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.nis1.thesis.sdk.ModuleHelper;

/**
 * Service for interacting with OpenDaylight RESTCONF API.
 */
public class OpenDaylightClient {

    private final ModuleHelper helper;
    private final String moduleName;
    private final String baseUrl;
    private final String username;
    private final String password;

    // Default SDN settings
    private static final String DEFAULT_NODE = "openflow:1";
    private static final int DEFAULT_TABLE = 0;
    private static final int ISOLATION_PRIORITY = 1000;

    public OpenDaylightClient(ModuleHelper helper, String moduleName, String baseUrl, String username,
            String password) {
        this.helper = helper;
        this.moduleName = moduleName;
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Isolate a host by installing a high-priority DROP flow.
     *
     * @param targetIp The IP to block
     * @return true if successful
     */
    public boolean isolateHost(String targetIp) {
        String flowId = "isolate-" + targetIp;
        String jsonPayload = buildIsolationFlowJson(flowId, targetIp);
        Set<String> candidateNodes = resolveCandidateNodesForIp(targetIp);
        boolean success = false;

        for (String nodeId : candidateNodes) {
            String url = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d/flow/%s",
                    baseUrl, nodeId, DEFAULT_TABLE, flowId);
            int responseCode = sendRestRequest("PUT", url, jsonPayload);
            if (responseCode >= 200 && responseCode < 300) {
                success = true;
            }
        }

        if (!success) {
            helper.log(moduleName, "ERROR", "Failed to install isolation flow on all candidate nodes for " + targetIp);
        }

        return success;
    }

    /**
     * Remove isolation for a host.
     */
    public boolean removeIsolation(String targetIp) {
        String flowId = "isolate-" + targetIp;
        Set<String> candidateNodes = resolveCandidateNodesForIp(targetIp);
        candidateNodes.addAll(fetchAllOpenFlowNodes());

        boolean anyRemovedOrAlreadyAbsent = false;
        for (String nodeId : candidateNodes) {
            String url = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d/flow/%s",
                    baseUrl, nodeId, DEFAULT_TABLE, flowId);
            int responseCode = sendRestRequest("DELETE", url, null);
            if ((responseCode >= 200 && responseCode < 300) || responseCode == 404) {
                anyRemovedOrAlreadyAbsent = true;
            }
        }

        return anyRemovedOrAlreadyAbsent;
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

    private Set<String> resolveCandidateNodesForIp(String targetIp) {
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
                            if (!hostMatchesIp(addresses, targetIp)) {
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

        helper.log(moduleName, "DEBUG", "Candidate ODL nodes for " + targetIp + ": " + nodes);
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

    private String buildIsolationFlowJson(String flowId, String ipAddress) {
        // Construct JSON manually to avoid extra dependencies if possible,
        // or usage of org.json if available in classpath
        return "{\n" +
                "  \"flow\": [\n" +
                "    {\n" +
                "      \"id\": \"" + flowId + "\",\n" +
                "      \"table_id\": " + DEFAULT_TABLE + ",\n" +
                "      \"priority\": " + ISOLATION_PRIORITY + ",\n" +
                "      \"match\": {\n" +
                "        \"ipv4-source\": \"" + ipAddress + "/32\",\n" +
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
}
