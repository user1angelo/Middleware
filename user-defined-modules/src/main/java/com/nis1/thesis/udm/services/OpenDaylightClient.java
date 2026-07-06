package com.nis1.thesis.udm.services;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    public static class RemoveIsolationResult {
        public final boolean success;
        public final String resolvedMitigationId;
        public final String targetKey;
        public final int matchedFlows;
        public final int deletedHttp2xx;

        private RemoveIsolationResult(boolean success, String resolvedMitigationId, String targetKey, int matchedFlows,
                int deletedHttp2xx) {
            this.success = success;
            this.resolvedMitigationId = resolvedMitigationId;
            this.targetKey = targetKey;
            this.matchedFlows = matchedFlows;
            this.deletedHttp2xx = deletedHttp2xx;
        }
    }

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

    private static class FlowEntry {
        private final String flowId;
        private final JSONObject match;

        private FlowEntry(String flowId, JSONObject match) {
            this.flowId = flowId;
            this.match = match;
        }
    }

    private final ModuleHelper helper;
    private final String moduleName;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final boolean suppressByIdOnly;

    // Default SDN settings
    private static final String DEFAULT_NODE = "openflow:1";
    private static final int DEFAULT_TABLE = 0;
    private static final int ISOLATION_PRIORITY = 1000;
    private static final String SYSTEM_FLOW_PREFIX = "sysq";
    private static final int FLOW_REQUEST_MAX_RETRIES = 3;
    private static final long FLOW_REQUEST_BACKOFF_BASE_MS = 300L;
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final String PERSIST_FILE = System.getProperty("user.home") + "/.middleware_odl_mitigations.json";

    private final Map<String, MitigationRecord> ownedMitigations = new ConcurrentHashMap<>();
    private final Map<String, String> targetIndex = new ConcurrentHashMap<>();

    public OpenDaylightClient(ModuleHelper helper, String moduleName, String baseUrl, String username,
            String password) {
        this(helper, moduleName, baseUrl, username, password, true);
    }

    public OpenDaylightClient(ModuleHelper helper, String moduleName, String baseUrl, String username,
            String password, boolean suppressByIdOnly) {
        this.helper = helper;
        this.moduleName = moduleName;
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.suppressByIdOnly = suppressByIdOnly;
    }

    public boolean isolateHost(String targetIp, String targetMac, String mitigationId, QuarantinePolicyOptions options) {
        String normalizedIp = normalizeIp(targetIp);
        String normalizedMac = normalizeMac(targetMac);
        String normalizedMitigationId = mitigationId != null && !mitigationId.isBlank()
                ? mitigationId.trim()
                : "mit-" + System.currentTimeMillis();
        String targetKey = buildTargetKey(normalizedIp, normalizedMac);

        if (shouldSuppressDuplicateByMitigationId(normalizedMitigationId, targetKey)) {
            return true;
        }

        if (!suppressByIdOnly) {
            String activeMitigationForTarget = targetIndex.get(targetKey);
            if (activeMitigationForTarget != null) {
                if (activeMitigationForTarget.equals(normalizedMitigationId)) {
                    helper.log(moduleName, "WARN", "Duplicate mitigation request suppressed (same mitigation_id already indexed): "
                            + normalizedMitigationId + " target=" + targetKey);
                    return true;
                }

                MitigationRecord activeRecord = ownedMitigations.get(activeMitigationForTarget);
                if (activeRecord != null && !activeRecord.installedFlows.isEmpty()) {
                    helper.log(moduleName, "WARN", "Target already has an active mitigation with different ID (target=" + targetKey
                            + ", active=" + activeMitigationForTarget + ", incoming=" + normalizedMitigationId
                            + "); removing old flows before installing new mitigation");
                    removeIsolationDetailed(normalizedIp, normalizedMac, activeMitigationForTarget);
                } else {
                    helper.log(moduleName, "WARN", "Target index was stale for " + targetKey
                            + " (mitigation=" + activeMitigationForTarget + "); continuing with fresh mitigation "
                            + normalizedMitigationId);
                    targetIndex.remove(targetKey, activeMitigationForTarget);
                }
            }
        }

        // Proactive cleanup: when in-memory state was lost (e.g. module restart),
        // scan ODL for orphaned sysq_ flows targeting this host before installing
        // new ones. This prevents stale flow accumulation across restarts.
        if (!suppressByIdOnly && targetIndex.get(targetKey) == null) {
            PrefixCleanupResult preCleanup = removeFlowsByTargetScan(normalizedIp, normalizedMac);
            if (preCleanup.matchedFlows > 0) {
                helper.log(moduleName, "INFO", "Pre-install target scan removed " + preCleanup.matchedFlows
                        + " orphaned sysq_ flow(s) for target " + targetKey
                        + " (likely from a prior module restart)");
            }
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
                int responseCode = sendFlowRequestWithRetry("PUT", nodeId, flowId, payload);
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

        persistState();

        helper.log(moduleName, "INFO", "Quarantine drop rules applied: " + installedCount
                + " (mitigation_id=" + normalizedMitigationId + ", mode=" + effectiveOptions.mode
                + ", arp=" + effectiveOptions.containArp + ", dhcp=" + effectiveOptions.containDhcp + ")");
        return true;
    }

    public boolean removeIsolation(String targetIp, String targetMac, String mitigationId) {
        return removeIsolationDetailed(targetIp, targetMac, mitigationId).success;
    }

    public RemoveIsolationResult removeIsolationDetailed(String targetIp, String targetMac, String mitigationId) {
        String normalizedIp = normalizeIp(targetIp);
        String normalizedMac = normalizeMac(targetMac);
        String targetKey = buildTargetKey(normalizedIp, normalizedMac);
        String resolvedMitigationId = mitigationId != null && !mitigationId.isBlank()
                ? mitigationId.trim()
                : targetIndex.get(targetKey);

        // Auto-isolation workflows may emit a non-persistent external identifier.
        // If provided mitigation ID is unknown, prefer the target-indexed active mitigation.
        if (resolvedMitigationId != null && !ownedMitigations.containsKey(resolvedMitigationId)) {
            String indexedMitigationId = targetIndex.get(targetKey);
            if (indexedMitigationId != null && !indexedMitigationId.equals(resolvedMitigationId)) {
                helper.log(moduleName, "WARN", "Mitigation ID mismatch for target " + targetKey
                        + " (provided=" + resolvedMitigationId + ", indexed=" + indexedMitigationId
                        + "); using indexed mitigation for cleanup");
                resolvedMitigationId = indexedMitigationId;
            }
        }

        if (resolvedMitigationId == null) {
            helper.log(moduleName, "WARN", "No owned mitigation record found for target " + targetKey
                    + "; performing target-based scan for orphaned sysq_ flows");
            PrefixCleanupResult cleanup = removeFlowsByTargetScan(normalizedIp, normalizedMac);
            if (cleanup.success) {
                evictTargetFromMemory(normalizedIp, normalizedMac);
                persistState();
            }
            return new RemoveIsolationResult(cleanup.success, null, targetKey,
                    cleanup.matchedFlows, cleanup.deletedHttp2xx);
        }

        MitigationRecord record = ownedMitigations.get(resolvedMitigationId);
        // Prefer the record's own mitigationId for prefix-based operations;
        // this defends against callers supplying a non-matching external ID.
        String recordMitigationId = record != null ? record.mitigationId : resolvedMitigationId;
        if (record == null || record.installedFlows.isEmpty()) {
            helper.log(moduleName, "WARN", "No in-memory flow records found for mitigation " + recordMitigationId
                    + "; falling back to prefix-based cleanup");
            PrefixCleanupResult cleanup = removeOwnedFlowsByMitigationPrefixDetailed(recordMitigationId);
            if (cleanup.matchedFlows == 0 && cleanup.deletedHttp2xx == 0) {
                helper.log(moduleName, "WARN", "Prefix cleanup found no flows for mitigation " + recordMitigationId
                        + "; falling back to target-based scan for " + targetKey);
                cleanup = removeFlowsByTargetScan(normalizedIp, normalizedMac);
            }
            if (cleanup.success) {
                bestEffortVerifyFlowsClearedBeforeEvict(recordMitigationId);
                evictMitigationFromMemoryByPrefix(recordMitigationId);
                evictTargetFromMemory(normalizedIp, normalizedMac);
                persistState();
            }

            return new RemoveIsolationResult(cleanup.success, recordMitigationId, targetKey,
                    cleanup.matchedFlows, cleanup.deletedHttp2xx);
        }

        boolean allRemovedOrAbsent = true;
        int deletedHttp2xx = 0;
        int matchedFlows = 0;
        for (OwnedFlow flow : record.installedFlows) {
            matchedFlows++;
            int responseCode = sendFlowRequestWithRetry("DELETE", flow.nodeId, flow.flowId, null);
            if (responseCode >= 200 && responseCode < 300) {
                deletedHttp2xx++;
            }
            if (!((responseCode >= 200 && responseCode < 300) || responseCode == 404)) {
                allRemovedOrAbsent = false;
            }
        }

        if (allRemovedOrAbsent) {
            if (matchedFlows == 0 || deletedHttp2xx == 0) {
                helper.log(moduleName, "WARN", "REMOVE_MITIGATION for mitigation " + recordMitigationId
                        + " target " + targetKey
                        + ": no flows were found or deleted — isolation may not have been active");
            }
            bestEffortVerifyFlowsClearedBeforeEvict(recordMitigationId);
            evictMitigationFromMemory(recordMitigationId);
            persistState();
            helper.log(moduleName, "INFO", "Removed system-owned quarantine rules for mitigation " + recordMitigationId);
        } else {
            helper.log(moduleName, "WARN", "Some tracked flow deletions failed for mitigation " + recordMitigationId
                    + "; attempting prefix-based cleanup fallback");
            PrefixCleanupResult cleanup = removeOwnedFlowsByMitigationPrefixDetailed(recordMitigationId);
            deletedHttp2xx += cleanup.deletedHttp2xx;
            matchedFlows += cleanup.matchedFlows;
            if (cleanup.success) {
                bestEffortVerifyFlowsClearedBeforeEvict(recordMitigationId);
                evictMitigationFromMemoryByPrefix(recordMitigationId);
                persistState();
                helper.log(moduleName, "INFO", "Fallback cleanup completed for mitigation " + recordMitigationId);
                return new RemoveIsolationResult(true, recordMitigationId, targetKey, matchedFlows, deletedHttp2xx);
            }
            helper.log(moduleName, "ERROR", "Failed to remove some system-owned quarantine rules for mitigation " + recordMitigationId);
        }

        return new RemoveIsolationResult(allRemovedOrAbsent, recordMitigationId, targetKey, matchedFlows, deletedHttp2xx);
    }

    public boolean applyProtocolDrop(String sourceIp, int ipProtocol, String mitigationId) {
        String normalizedIp = normalizeIp(sourceIp);
        if (normalizedIp == null) {
            helper.log(moduleName, "ERROR", "Cannot apply protocol drop: missing source IP");
            return false;
        }

        String normalizedMitigationId = mitigationId != null && !mitigationId.isBlank()
                ? mitigationId.trim()
                : "mit-" + System.currentTimeMillis();
        String targetKey = buildTargetKey(normalizedIp, null);

        if (shouldSuppressDuplicateByMitigationId(normalizedMitigationId, targetKey)) {
            return true;
        }

        if (!suppressByIdOnly) {
            String activeMitigationForTarget = targetIndex.get(targetKey);
            if (activeMitigationForTarget != null) {
                if (activeMitigationForTarget.equals(normalizedMitigationId)) {
                    helper.log(moduleName, "WARN", "Duplicate mitigation request suppressed (same mitigation_id already indexed): "
                            + normalizedMitigationId + " target=" + targetKey);
                    return true;
                }

                MitigationRecord activeRecord = ownedMitigations.get(activeMitigationForTarget);
                if (activeRecord != null && !activeRecord.installedFlows.isEmpty()) {
                    helper.log(moduleName, "WARN", "Target already has an active mitigation with different ID (target=" + targetKey
                            + ", active=" + activeMitigationForTarget + ", incoming=" + normalizedMitigationId
                            + "); removing old flows before installing new protocol drop");
                    removeIsolationDetailed(normalizedIp, null, activeMitigationForTarget);
                } else {
                    targetIndex.remove(targetKey, activeMitigationForTarget);
                }
            }
        }

        Set<String> candidateNodes = resolveCandidateNodesForTarget(normalizedIp, null);
        MitigationRecord record = new MitigationRecord(normalizedMitigationId, normalizedIp, null);

        int installedCount = 0;
        for (String nodeId : candidateNodes) {
            String flowToken = "proto_drop_" + ipProtocol;
            String flowId = buildSystemFlowId(normalizedMitigationId, flowToken);
            String payload = buildProtocolDropFlowJson(flowId, normalizedIp, ipProtocol);
            int responseCode = sendFlowRequestWithRetry("PUT", nodeId, flowId, payload);
            if (responseCode >= 200 && responseCode < 300) {
                installedCount++;
                record.installedFlows.add(new OwnedFlow(nodeId, flowId));
            }
        }

        if (record.installedFlows.isEmpty()) {
            helper.log(moduleName, "ERROR", "Failed to install protocol drop rule for mitigation " + normalizedMitigationId);
            return false;
        }

        ownedMitigations.put(normalizedMitigationId, record);
        targetIndex.put(targetKey, normalizedMitigationId);

        persistState();

        helper.log(moduleName, "INFO", "Protocol drop rules applied: " + installedCount
                + " (mitigation_id=" + normalizedMitigationId + ", ip=" + normalizedIp
                + ", ip_protocol=" + ipProtocol + ")");
        return true;
    }

    private boolean shouldSuppressDuplicateByMitigationId(String mitigationId, String targetKey) {
        MitigationRecord existingById = ownedMitigations.get(mitigationId);
        if (existingById != null && !existingById.installedFlows.isEmpty()) {
            helper.log(moduleName, "WARN", "Duplicate mitigation request suppressed (already active): "
                    + mitigationId + " target=" + targetKey);
            return true;
        }

        if (hasConfiguredFlowsForMitigationPrefix(mitigationId)) {
            helper.log(moduleName, "WARN", "Duplicate mitigation request suppressed (ODL still reports sysq_ flows for mitigation_id): "
                    + mitigationId + " target=" + targetKey);
            return true;
        }

        return false;
    }

    private static class PrefixCleanupResult {
        private final boolean success;
        private final int matchedFlows;
        private final int deletedHttp2xx;

        private PrefixCleanupResult(boolean success, int matchedFlows, int deletedHttp2xx) {
            this.success = success;
            this.matchedFlows = matchedFlows;
            this.deletedHttp2xx = deletedHttp2xx;
        }
    }

    private PrefixCleanupResult removeOwnedFlowsByMitigationPrefixDetailed(String mitigationId) {
        String mitigationPrefix = SYSTEM_FLOW_PREFIX + "_" + sanitize(mitigationId) + "_";
        Set<String> nodes = fetchAllOpenFlowNodes();
        if (nodes.isEmpty()) {
            nodes.add(DEFAULT_NODE);
        }

        int deletionFailures = 0;
        int matchedFlows = 0;
        int deletedHttp2xx = 0;

        for (String nodeId : nodes) {
            Set<String> flowIds = fetchConfiguredFlowIdsForNode(nodeId);
            for (String flowId : flowIds) {
                if (!flowId.startsWith(mitigationPrefix)) {
                    continue;
                }

                matchedFlows++;
                int responseCode = sendFlowRequestWithRetry("DELETE", nodeId, flowId, null);
                if (responseCode >= 200 && responseCode < 300) {
                    deletedHttp2xx++;
                }
                if (!((responseCode >= 200 && responseCode < 300) || responseCode == 404)) {
                    deletionFailures++;
                }
            }
        }

        if (matchedFlows == 0) {
            helper.log(moduleName, "WARN", "REMOVE_MITIGATION for mitigation " + mitigationId
                    + ": no configured flows matched mitigation prefix " + mitigationPrefix
                    + " (already removed or never installed)");
            return new PrefixCleanupResult(true, 0, 0);
        }

        if (deletionFailures > 0) {
            helper.log(moduleName, "ERROR", "Prefix cleanup failed for " + deletionFailures + " flow(s) under " + mitigationPrefix);
            return new PrefixCleanupResult(false, matchedFlows, deletedHttp2xx);
        }

        helper.log(moduleName, "INFO", "Prefix cleanup removed " + matchedFlows + " flow(s) under " + mitigationPrefix);
        return new PrefixCleanupResult(true, matchedFlows, deletedHttp2xx);
    }

    private PrefixCleanupResult removeFlowsByTargetScan(String targetIp, String targetMac) {
        String normalizedIp = normalizeIp(targetIp);
        String normalizedMac = normalizeMac(targetMac);

        if (normalizedIp == null && normalizedMac == null) {
            helper.log(moduleName, "WARN", "Target scan requires at least IP or MAC");
            return new PrefixCleanupResult(false, 0, 0);
        }

        Set<String> nodes = fetchAllOpenFlowNodes();
        if (nodes.isEmpty()) {
            nodes.add(DEFAULT_NODE);
        }

        int matchedFlows = 0;
        int deletedHttp2xx = 0;
        int deletionFailures = 0;

        for (String nodeId : nodes) {
            List<FlowEntry> sysqFlows = fetchSysqFlowsWithMatch(nodeId);
            for (FlowEntry entry : sysqFlows) {
                if (!flowMatchesTarget(entry.match, normalizedIp, normalizedMac)) {
                    continue;
                }

                matchedFlows++;
                int responseCode = sendFlowRequestWithRetry("DELETE", nodeId, entry.flowId, null);
                if (responseCode >= 200 && responseCode < 300) {
                    deletedHttp2xx++;
                }
                if (!((responseCode >= 200 && responseCode < 300) || responseCode == 404)) {
                    deletionFailures++;
                }
            }
        }

        if (matchedFlows == 0) {
            helper.log(moduleName, "DEBUG", "Target scan: no sysq_ flows found matching target "
                    + buildTargetKey(normalizedIp, normalizedMac));
            return new PrefixCleanupResult(true, 0, 0);
        }

        if (deletionFailures > 0) {
            helper.log(moduleName, "ERROR", "Target scan: failed to delete " + deletionFailures
                    + " flow(s) for target " + buildTargetKey(normalizedIp, normalizedMac));
            return new PrefixCleanupResult(false, matchedFlows, deletedHttp2xx);
        }

        helper.log(moduleName, "INFO", "Target scan removed " + matchedFlows
                + " sysq_ flow(s) for target " + buildTargetKey(normalizedIp, normalizedMac));
        return new PrefixCleanupResult(true, matchedFlows, deletedHttp2xx);
    }

    private boolean hasConfiguredFlowsForMitigationPrefix(String mitigationId) {
        String mitigationPrefix = SYSTEM_FLOW_PREFIX + "_" + sanitize(mitigationId) + "_";
        Set<String> nodes = fetchAllOpenFlowNodes();
        if (nodes.isEmpty()) {
            nodes.add(DEFAULT_NODE);
        }

        for (String nodeId : nodes) {
            Set<String> flowIds = fetchConfiguredFlowIdsForNodeBestEffort(nodeId);
            for (String flowId : flowIds) {
                if (flowId.startsWith(mitigationPrefix)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void bestEffortVerifyFlowsClearedBeforeEvict(String mitigationId) {
        try {
            String mitigationPrefix = SYSTEM_FLOW_PREFIX + "_" + sanitize(mitigationId) + "_";
            Set<String> nodes = fetchAllOpenFlowNodes();
            if (nodes.isEmpty()) {
                nodes.add(DEFAULT_NODE);
            }

            int remaining = 0;
            for (String nodeId : nodes) {
                Set<String> flowIds = fetchConfiguredFlowIdsForNodeBestEffort(nodeId);
                for (String flowId : flowIds) {
                    if (flowId.startsWith(mitigationPrefix)) {
                        remaining++;
                    }
                }
            }

            if (remaining > 0) {
                helper.log(moduleName, "DEBUG", "Post-delete verification: ODL still reports " + remaining
                        + " flow(s) under prefix " + mitigationPrefix
                        + " after REMOVE_MITIGATION; proceeding with in-memory eviction anyway");
            }
        } catch (Exception e) {
            helper.log(moduleName, "DEBUG", "Post-delete verification query failed for mitigation " + mitigationId
                    + ": " + e.getMessage());
        }
    }

    private void evictMitigationFromMemory(String mitigationId) {
        MitigationRecord record = ownedMitigations.remove(mitigationId);
        if (record != null) {
            targetIndex.remove(buildTargetKey(record.targetIp, record.targetMac), mitigationId);
        }

        // Also remove any target index entries pointing to this mitigation (defensive).
        for (Map.Entry<String, String> entry : targetIndex.entrySet()) {
            if (mitigationId.equals(entry.getValue())) {
                targetIndex.remove(entry.getKey(), mitigationId);
            }
        }
    }

    private void evictMitigationFromMemoryByPrefix(String mitigationId) {
        String sanitized = sanitize(mitigationId);
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, MitigationRecord> entry : ownedMitigations.entrySet()) {
            MitigationRecord record = entry.getValue();
            if (record == null) {
                continue;
            }
            if (sanitize(record.mitigationId).equals(sanitized)) {
                toRemove.add(entry.getKey());
                continue;
            }

            String mitigationPrefix = SYSTEM_FLOW_PREFIX + "_" + sanitized + "_";
            for (OwnedFlow flow : record.installedFlows) {
                if (flow.flowId != null && flow.flowId.startsWith(mitigationPrefix)) {
                    toRemove.add(entry.getKey());
                    break;
                }
            }
        }

        for (String id : toRemove) {
            evictMitigationFromMemory(id);
        }

        // Ensure the explicit key is evicted too.
        evictMitigationFromMemory(mitigationId);
    }

    private void evictTargetFromMemory(String normalizedIp, String normalizedMac) {
        String targetKey = buildTargetKey(normalizedIp, normalizedMac);
        String existingId = targetIndex.remove(targetKey);
        if (existingId != null) {
            ownedMitigations.remove(existingId);
        }

        // Defensive: remove any records keyed by this target even if the
        // mitigation ID differs.
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, MitigationRecord> entry : ownedMitigations.entrySet()) {
            MitigationRecord record = entry.getValue();
            if (record != null) {
                String recordKey = buildTargetKey(record.targetIp, record.targetMac);
                if (recordKey.equals(targetKey)) {
                    toRemove.add(entry.getKey());
                }
            }
        }

        for (String id : toRemove) {
            ownedMitigations.remove(id);
        }
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

    private Set<String> fetchConfiguredFlowIdsForNodeBestEffort(String nodeId) {
        Set<String> flowIds = new LinkedHashSet<>();
        String tableUrl = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d", baseUrl, nodeId,
                DEFAULT_TABLE);

        try {
            JSONObject response = fetchJsonBestEffort(tableUrl);
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
            helper.log(moduleName, "DEBUG", "Best-effort flow query failed for node " + nodeId + ": " + e.getMessage());
        }

        return flowIds;
    }

    private List<FlowEntry> fetchSysqFlowsWithMatch(String nodeId) {
        List<FlowEntry> entries = new ArrayList<>();
        String tableUrl = String.format("%s/restconf/config/opendaylight-inventory:nodes/node/%s/table/%d",
                baseUrl, nodeId, DEFAULT_TABLE);

        try {
            JSONObject response = fetchJson(tableUrl);
            if (response == null) {
                return entries;
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
                        if (!id.startsWith(SYSTEM_FLOW_PREFIX + "_")) {
                            continue;
                        }

                        JSONObject match = flow.optJSONObject("match");
                        if (match != null) {
                            entries.add(new FlowEntry(id, match));
                        }
                    }
                }
            }
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "Failed to fetch sysq_ flows for node " + nodeId + ": " + e.getMessage());
        }

        return entries;
    }

    private boolean flowMatchesTarget(JSONObject match, String targetIp, String targetMac) {
        if (match == null) {
            return false;
        }

        if (targetIp != null) {
            String ipSrc = extractIpFromMatch(match, "ipv4-source");
            if (targetIp.equals(ipSrc)) {
                return true;
            }

            String ipDst = extractIpFromMatch(match, "ipv4-destination");
            if (targetIp.equals(ipDst)) {
                return true;
            }

            String arpSpa = match.optString("arp-source-transport-address", "");
            if (arpSpa.startsWith(targetIp + "/") || arpSpa.equals(targetIp)) {
                return true;
            }

            String arpTpa = match.optString("arp-target-transport-address", "");
            if (arpTpa.startsWith(targetIp + "/") || arpTpa.equals(targetIp)) {
                return true;
            }
        }

        if (targetMac != null) {
            JSONObject ethMatch = match.optJSONObject("ethernet-match");
            if (ethMatch != null) {
                String srcMac = extractMacFromEthernetMatch(ethMatch, "ethernet-source");
                if (targetMac.equalsIgnoreCase(srcMac)) {
                    return true;
                }

                String dstMac = extractMacFromEthernetMatch(ethMatch, "ethernet-destination");
                if (targetMac.equalsIgnoreCase(dstMac)) {
                    return true;
                }
            }
        }

        return false;
    }

    private String extractIpFromMatch(JSONObject match, String key) {
        String value = match.optString(key, "");
        if (value.isBlank()) {
            return null;
        }
        int slashIdx = value.indexOf('/');
        return slashIdx >= 0 ? value.substring(0, slashIdx) : value;
    }

    private String extractMacFromEthernetMatch(JSONObject ethMatch, String key) {
        JSONObject field = ethMatch.optJSONObject(key);
        if (field == null) {
            return null;
        }
        return field.optString("address", null);
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

    private JSONObject fetchJsonBestEffort(String urlStr) {
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
            helper.log(moduleName, "DEBUG", "Best-effort GET JSON request failed for " + urlStr + ": " + e.getMessage());
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

    private int sendFlowRequestWithRetry(String method, String nodeId, String flowId, String jsonBody) {
        int attempt = 0;
        int lastResponse = -1;

        while (attempt < FLOW_REQUEST_MAX_RETRIES) {
            attempt++;
            lastResponse = sendFlowRequest(method, nodeId, flowId, jsonBody);

            boolean successOrAbsent = (lastResponse >= 200 && lastResponse < 300)
                    || ("DELETE".equals(method) && lastResponse == 404);
            if (successOrAbsent) {
                return lastResponse;
            }

            if (attempt >= FLOW_REQUEST_MAX_RETRIES) {
                break;
            }

            long delayMs = FLOW_REQUEST_BACKOFF_BASE_MS * (1L << (attempt - 1));
            helper.log(moduleName, "WARN", "Flow request retry " + attempt + "/" + FLOW_REQUEST_MAX_RETRIES
                    + " for " + method + " flow " + flowId + " on " + nodeId
                    + " (response=" + lastResponse + ", next_delay_ms=" + delayMs + ")");
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return lastResponse;
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

    private String buildProtocolDropFlowJson(String flowId, String ipAddress, int ipProtocol) {
        JSONObject match = new JSONObject();
        match.put("ipv4-source", ipAddress + "/32");
        match.put("ethernet-match", ethernetType(2048));
        match.put("ip-match", new JSONObject().put("ip-protocol", ipProtocol));

        JSONObject flow = new JSONObject();
        flow.put("id", flowId);
        flow.put("table_id", DEFAULT_TABLE);
        flow.put("priority", ISOLATION_PRIORITY);
        flow.put("match", match);
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

    // -------------------------------------------------------------------------
    // Mitigation state persistence
    // -------------------------------------------------------------------------

    private void persistState() {
        Path path = Paths.get(PERSIST_FILE);
        JSONObject root = new JSONObject();
        root.put("version", 1);

        JSONObject mitigations = new JSONObject();
        for (Map.Entry<String, MitigationRecord> entry : ownedMitigations.entrySet()) {
            MitigationRecord record = entry.getValue();
            if (record == null || record.installedFlows.isEmpty()) {
                continue;
            }

            JSONObject recJson = new JSONObject();
            recJson.put("mitigationId", record.mitigationId);
            recJson.put("targetIp", record.targetIp != null ? record.targetIp : "");
            recJson.put("targetMac", record.targetMac != null ? record.targetMac : "");

            JSONArray flowsArray = new JSONArray();
            for (OwnedFlow flow : record.installedFlows) {
                JSONObject flowJson = new JSONObject();
                flowJson.put("nodeId", flow.nodeId);
                flowJson.put("flowId", flow.flowId);
                flowsArray.put(flowJson);
            }
            recJson.put("installedFlows", flowsArray);
            mitigations.put(entry.getKey(), recJson);
        }
        root.put("ownedMitigations", mitigations);

        JSONObject indexes = new JSONObject();
        for (Map.Entry<String, String> entry : targetIndex.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                indexes.put(entry.getKey(), entry.getValue());
            }
        }
        root.put("targetIndex", indexes);

        try {
            Files.writeString(path, root.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            helper.log(moduleName, "WARN", "Failed to persist ODL mitigation state: " + e.getMessage());
        }
    }

    public void loadPersistedState() {
        Path path = Paths.get(PERSIST_FILE);
        if (!Files.exists(path)) {
            return;
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);

            JSONObject mitigations = root.optJSONObject("ownedMitigations");
            if (mitigations != null) {
                for (String key : mitigations.keySet()) {
                    JSONObject recJson = mitigations.optJSONObject(key);
                    if (recJson == null) {
                        continue;
                    }

                    String mitigationId = recJson.optString("mitigationId", key);
                    String targetIp = recJson.optString("targetIp", "");
                    String targetMac = recJson.optString("targetMac", "");
                    if (targetIp.isEmpty()) {
                        targetIp = null;
                    }
                    if (targetMac.isEmpty()) {
                        targetMac = null;
                    }

                    MitigationRecord record = new MitigationRecord(mitigationId, targetIp, targetMac);
                    JSONArray flowsArray = recJson.optJSONArray("installedFlows");
                    if (flowsArray != null) {
                        for (int i = 0; i < flowsArray.length(); i++) {
                            JSONObject flowJson = flowsArray.optJSONObject(i);
                            if (flowJson == null) {
                                continue;
                            }
                            String nodeId = flowJson.optString("nodeId", "");
                            String flowId = flowJson.optString("flowId", "");
                            if (!nodeId.isBlank() && !flowId.isBlank()) {
                                record.installedFlows.add(new OwnedFlow(nodeId, flowId));
                            }
                        }
                    }

                    if (!record.installedFlows.isEmpty()) {
                        // Verify flows still exist on ODL; if any survived a
                        // restart we restore the record, otherwise discard.
                        boolean atLeastOneExists = false;
                        for (OwnedFlow flow : record.installedFlows) {
                            int code = sendFlowRequest("GET", flow.nodeId, flow.flowId, null);
                            if (code >= 200 && code < 300) {
                                atLeastOneExists = true;
                                break;
                            }
                        }

                        if (atLeastOneExists) {
                            ownedMitigations.put(mitigationId, record);
                            String targetKey = buildTargetKey(targetIp, targetMac);
                            targetIndex.put(targetKey, mitigationId);
                        }
                    }
                }
            }

            // Also restore targetIndex entries from the persisted index
            JSONObject indexes = root.optJSONObject("targetIndex");
            if (indexes != null) {
                for (String key : indexes.keySet()) {
                    String indexedId = indexes.optString(key, null);
                    if (indexedId != null && ownedMitigations.containsKey(indexedId)) {
                        targetIndex.putIfAbsent(key, indexedId);
                    }
                }
            }

            if (!ownedMitigations.isEmpty()) {
                helper.log(moduleName, "INFO",
                        "Restored " + ownedMitigations.size() + " mitigation record(s) from persisted state");
            }
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "Failed to load persisted ODL mitigation state: " + e.getMessage());
        }
    }

    public Map<String, List<String>> checkConflictingIpMacBindings() {
        Map<String, List<String>> ipToMacs = new java.util.HashMap<>();
        try {
            JSONObject topology = fetchTopology();
            if (topology != null) {
                JSONObject networkTopology = topology.optJSONObject("network-topology");
                JSONArray topologies = networkTopology != null ? networkTopology.optJSONArray("topology") : null;
                if (topologies != null) {
                    for (int i = 0; i < topologies.length(); i++) {
                        JSONObject topo = topologies.optJSONObject(i);
                        if (topo == null) continue;
                        JSONArray nodeArray = topo.optJSONArray("node");
                        if (nodeArray == null) continue;
                        for (int j = 0; j < nodeArray.length(); j++) {
                            JSONObject node = nodeArray.optJSONObject(j);
                            if (node == null) continue;
                            String nodeId = node.optString("node-id", "");
                            if (!nodeId.startsWith("host:")) continue;
                            String mac = nodeId.replace("host:", "").toLowerCase();
                            JSONArray addresses = node.optJSONArray("host-tracker-service:addresses");
                            if (addresses != null) {
                                for (int k = 0; k < addresses.length(); k++) {
                                    JSONObject addr = addresses.optJSONObject(k);
                                    if (addr != null) {
                                        String ip = addr.optString("ip", "");
                                        if (!ip.isEmpty()) {
                                            ipToMacs.computeIfAbsent(ip, x -> new ArrayList<>()).add(mac);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            helper.log(moduleName, "WARN", "Failed to check IP-MAC bindings: " + e.getMessage());
        }
        return ipToMacs;
    }
}
