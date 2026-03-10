package com.nis1.thesis.udm.services;

import com.nis1.thesis.sdk.ModuleHelper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
        return isolateHost(targetIp, null);
    }

    /**
     * Isolate a host using flow IDs that are tied to a mitigation record.
     */
    public boolean isolateHost(String targetIp, String mitigationId) {
        if (targetIp == null || targetIp.trim().isEmpty()) {
            helper.log(moduleName, "ERROR", "Cannot isolate empty target host");
            return false;
        }

        String flowBase = buildFlowBase(targetIp, mitigationId);
        String srcFlowId = flowBase + "-src";
        String dstFlowId = flowBase + "-dst";

        boolean srcOk = putFlow(srcFlowId, buildIsolationFlowJson(srcFlowId, targetIp, true));
        boolean dstOk = putFlow(dstFlowId, buildIsolationFlowJson(dstFlowId, targetIp, false));

        return srcOk && dstOk;
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
        if (targetIp == null || targetIp.trim().isEmpty()) {
            helper.log(moduleName, "ERROR", "Cannot remove isolation for empty target host");
            return false;
        }

        String flowBase = buildFlowBase(targetIp, mitigationId);
        boolean srcOk = deleteFlow(flowBase + "-src");
        boolean dstOk = deleteFlow(flowBase + "-dst");
        return srcOk && dstOk;
    }

    private boolean putFlow(String flowId, String jsonPayload) {
        String url = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d/flow/%s",
                baseUrl, DEFAULT_NODE, DEFAULT_TABLE, flowId);
        return sendRestRequest("PUT", url, jsonPayload);
    }

    private boolean deleteFlow(String flowId) {
        String url = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d/flow/%s",
                baseUrl, DEFAULT_NODE, DEFAULT_TABLE, flowId);
        return sendRestRequest("DELETE", url, null);
    }

    private String buildFlowBase(String targetIp, String mitigationId) {
        String idPart = mitigationId != null && !mitigationId.trim().isEmpty()
                ? sanitize(mitigationId)
                : "host-" + sanitize(targetIp);
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

    private String buildIsolationFlowJson(String flowId, String ipAddress, boolean sourceMatch) {
        String matchKey = sourceMatch ? "ipv4-source" : "ipv4-destination";
        // Construct JSON manually to avoid extra dependencies if possible,
        // or usage of org.json if available in classpath
        return "{\n" +
                "  \"flow\": [\n" +
                "    {\n" +
                "      \"id\": \"" + flowId + "\",\n" +
                "      \"table_id\": " + DEFAULT_TABLE + ",\n" +
                "      \"priority\": " + ISOLATION_PRIORITY + ",\n" +
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
}
