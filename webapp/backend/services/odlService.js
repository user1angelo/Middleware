const http = require('http');
const fs = require('fs');
const amqp = require('amqplib');
require('dotenv').config();

// Configuration
const SCAN_RESULTS_PATH = '/tmp/middleware_scan_results.json';
const ODL_HOST = process.env.ODL_HOST || 'localhost';
const ODL_PORT = process.env.ODL_PORT || 8181;
const ODL_USER = process.env.ODL_USER || 'admin';
const ODL_PASSWORD = process.env.ODL_PASSWORD || 'admin';

const RABBITMQ_HOST = process.env.RABBITMQ_HOST || '127.0.0.1';
const RABBITMQ_PORT = process.env.RABBITMQ_PORT || 5672;
const RABBITMQ_USER = process.env.RABBITMQ_USER || 'user';
const RABBITMQ_PASS = process.env.RABBITMQ_PASSWORD || 'password';
const COMMAND_QUEUE = 'workflow_response_queue';
const MITIGATION_EVENTS_QUEUE = process.env.MITIGATION_EVENTS_QUEUE || 'webapp_mitigation_events_queue';
const DEFAULT_NODE = process.env.ODL_DEFAULT_NODE || 'openflow:1';
const DEFAULT_POLICY_MODE = process.env.ODL_QUARANTINE_POLICY_MODE || 'strict_bi_directional';
const DEFAULT_CONTAIN_ARP = process.env.ODL_CONTAIN_ARP !== 'false';
const DEFAULT_CONTAIN_DHCP = process.env.ODL_CONTAIN_DHCP !== 'false';
const DEFAULT_AUTO_EXPIRE = process.env.ODL_AUTO_EXPIRE_DEFAULT === 'true';
const DEFAULT_DURATION_SECONDS = Number(process.env.ODL_DEFAULT_DURATION_SECONDS || 900);
const MAX_DURATION_SECONDS = Number(process.env.ODL_MAX_DURATION_SECONDS || 86400);

const LOCALIZATION_STATUS = {
    RESOLVED: 'resolved',
    PARTIAL: 'partial',
    FALLBACK: 'fallback'
};

class OdlService {
    constructor() {
        this.connection = null;
        this.channel = null;
        this.listenerChannel = null;
        this.connecting = false;
        this.activeMitigations = new Map();
        this.expiryTimers = new Map();
        this.connectRabbitMQ();
    }

    async connectRabbitMQ() {
        if (this.connecting) return;
        this.connecting = true;

        const maxRetries = 5;
        const retryDelay = 2000; // 2 seconds

        for (let attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                const url = `amqp://${RABBITMQ_USER}:${RABBITMQ_PASS}@${RABBITMQ_HOST}:${RABBITMQ_PORT}`;
                console.log(`🔌 Connecting to RabbitMQ at ${RABBITMQ_HOST}:${RABBITMQ_PORT} as user '${RABBITMQ_USER}'...`);
                this.connection = await amqp.connect(url);
                this.channel = await this.connection.createChannel();
                await this.channel.assertQueue(COMMAND_QUEUE, { durable: true });
                await this.startMitigationEventsListener();
                console.log('✅ OdlService connected to RabbitMQ');

                // Handle connection close/error events
                this.connection.on('close', () => {
                    console.warn('⚠️ RabbitMQ connection closed, reconnecting...');
                    this.channel = null;
                    this.listenerChannel = null;
                    this.connection = null;
                    this.connecting = false;
                    setTimeout(() => this.connectRabbitMQ(), retryDelay);
                });

                this.connection.on('error', (err) => {
                    console.error('❌ RabbitMQ connection error:', err.message);
                });

                this.connecting = false;
                return;
            } catch (error) {
                console.error(`❌ OdlService RabbitMQ connection attempt ${attempt}/${maxRetries} failed:`, error.message);
                if (attempt < maxRetries) {
                    console.log(`⏳ Retrying in ${retryDelay / 1000} seconds...`);
                    await new Promise(resolve => setTimeout(resolve, retryDelay));
                }
            }
        }
        this.connecting = false;
        console.error('❌ Failed to connect to RabbitMQ after all retries');
    }

    // Wait for channel to be ready with timeout
    async waitForChannel(timeoutMs = 5000) {
        const startTime = Date.now();
        while (!this.channel && (Date.now() - startTime) < timeoutMs) {
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        if (!this.channel) {
            // Try to reconnect if not already connecting
            if (!this.connecting) {
                this.connectRabbitMQ();
            }
            throw new Error('RabbitMQ channel not ready. Please ensure RabbitMQ is running and try again.');
        }
    }

    async startMitigationEventsListener() {
        if (!this.connection) {
            return;
        }

        if (this.listenerChannel) {
            return;
        }

        this.listenerChannel = await this.connection.createChannel();
        await this.listenerChannel.assertQueue(MITIGATION_EVENTS_QUEUE, { durable: true });
        await this.listenerChannel.prefetch(25);

        await this.listenerChannel.consume(MITIGATION_EVENTS_QUEUE, async (msg) => {
            if (!msg) {
                return;
            }

            try {
                const content = msg.content.toString('utf-8');
                const parsed = JSON.parse(content);
                this.applyMirroredMitigationEvent(parsed);
                this.listenerChannel.ack(msg);
            } catch (error) {
                console.error('❌ Failed to process mirrored mitigation event:', error.message);
                this.listenerChannel.nack(msg, false, false);
            }
        });

        console.log(`📡 OdlService listening for mitigation events on '${MITIGATION_EVENTS_QUEUE}'`);
    }

    applyMirroredMitigationEvent(event) {
        if (!event || typeof event !== 'object') {
            return;
        }

        const eventType = event.event_type;
        const payload = event.payload || {};

        if (eventType === 'INITIATE_MITIGATION') {
            this.registerMirroredMitigation(payload, event.event_id);
            return;
        }

        if (eventType === 'REMOVE_MITIGATION') {
            this.clearMirroredMitigation(payload);
        }
    }

    registerMirroredMitigation(payload, fallbackEventId) {
        const normalized = this.normalizeTarget(payload.ip_address || payload.targetHost, payload.mac_address);
        if (!normalized.ip && !normalized.mac) {
            return;
        }

        const policy = this.normalizePolicyOptions(payload.quarantine_policy || {});
        const lifecycle = this.normalizeLifecycleOptions(payload.lifecycle || {});
        const mitigationId = payload.mitigation_id || fallbackEventId || this.createMitigationId();
        const localization = payload.localization || {
            status: LOCALIZATION_STATUS.FALLBACK,
            confidence: 'low',
            node: DEFAULT_NODE,
            port: null,
            source_of_truth: 'mirrored_event',
            fallback_reason: 'Localization data not included in mirrored event'
        };

        const existing = this.activeMitigations.get(mitigationId);
        if (existing && existing.status === 'active') {
            return;
        }

        const existingByTarget = this.findActiveMitigationByTarget(normalized.ip, normalized.mac);
        if (existingByTarget && existingByTarget.mitigation_id !== mitigationId) {
            this.clearMitigationTimer(existingByTarget.mitigation_id);
            this.activeMitigations.delete(existingByTarget.mitigation_id);
        }

        this.registerMitigation({
            mitigationId,
            normalized,
            policy,
            lifecycle,
            localization
        });
    }

    clearMirroredMitigation(payload) {
        const mitigationId = payload.mitigation_id;

        if (mitigationId && this.activeMitigations.has(mitigationId)) {
            this.clearMitigationTimer(mitigationId);
            this.activeMitigations.delete(mitigationId);
            return;
        }

        const normalized = this.normalizeTarget(payload.ip_address || payload.targetHost, payload.mac_address);
        if (!normalized.ip && !normalized.mac) {
            return;
        }

        const match = this.findActiveMitigationByTarget(normalized.ip, normalized.mac);
        if (match) {
            this.clearMitigationTimer(match.mitigation_id);
            this.activeMitigations.delete(match.mitigation_id);
        }
    }

    // Fetch topology from ODL Restconf
    async getTopology() {
        const topology = await new Promise((resolve, reject) => {
            const options = {
                hostname: ODL_HOST,
                port: ODL_PORT,
                path: '/restconf/operational/network-topology:network-topology',
                method: 'GET',
                headers: {
                    'Authorization': 'Basic ' + Buffer.from(ODL_USER + ':' + ODL_PASSWORD).toString('base64'),
                    'Accept': 'application/json'
                }
            };

            const req = http.request(options, (res) => {
                let data = '';
                res.on('data', (chunk) => data += chunk);
                res.on('end', () => {
                    if (res.statusCode >= 200 && res.statusCode < 300) {
                        try {
                            const json = JSON.parse(data);
                            resolve(json);
                        } catch (e) {
                            reject(new Error('Invalid JSON response from ODL'));
                        }
                    } else {
                        reject(new Error(`ODL Error: ${res.statusCode} - ${data}`));
                    }
                });
            });

            req.on('error', (e) => {
                // If ODL is down, return a mock/empty topology rather than crashing
                console.warn('⚠️ ODL not reachable, returning simulated topology');
                resolve(this.getSimulatedTopology());
            });

            req.end();
        });

        const hosts = this.extractHostsFromTopology(topology);

        // Merge scan results (ping sweep discoveries not yet in ODL host-tracker)
        const scanResults = this.readScanResults();
        if (scanResults && scanResults.hosts.length > 0) {
            const existingIps = new Set(hosts.map(h => h.ip));
            for (const sh of scanResults.hosts) {
                if (sh.ip && !existingIps.has(sh.ip)) {
                    hosts.push({
                        id: 'host:' + (sh.mac && sh.mac !== 'Unknown' ? sh.mac : sh.ip.replace(/\./g, '-')),
                        mac: sh.mac || 'Unknown',
                        ip: sh.ip,
                        attachment: 'Scan Discovery',
                        localization: {
                            status: LOCALIZATION_STATUS.PARTIAL,
                            confidence: 'medium',
                            node: DEFAULT_NODE,
                            port: null,
                            source_of_truth: 'ping_sweep',
                            reason: 'Discovered via active ping sweep'
                        }
                    });
                }
            }
            console.log(`🔍 Merged ${scanResults.hosts.length} scan hosts into topology (${existingIps.size} already known from ODL)`);
        }

        return {
            ...topology,
            localization: {
                default_node: DEFAULT_NODE,
                source_of_truth: 'odl.topology'
            },
            hosts
        };
    }

    buildWorkflowMessage(eventType, payload) {
        const eventId = `manual-${eventType.toLowerCase()}-${Date.now()}`;
        return {
            message_type: 'workflow_command',
            event_id: eventId,
            timestamp: new Date().toISOString(),
            event_type: eventType,
            source_module: 'webapp.network_control',
            payload
        };
    }

    normalizeTarget(ip, mac) {
        const normalizedIp = this.extractIpv4(ip);
        const normalizedMac = mac && String(mac).trim() ? String(mac).trim().toLowerCase() : null;
        return { ip: normalizedIp, mac: normalizedMac };
    }

    extractIpv4(rawIp) {
        if (!rawIp || !String(rawIp).trim()) {
            return null;
        }

        const text = String(rawIp).trim();
        if (text.includes('[MISSING:')) {
            return null;
        }

        const match = text.match(/\b(?:\d{1,3}\.){3}\d{1,3}\b/);
        if (!match) {
            return null;
        }

        return match[0];
    }

    normalizePolicyOptions(policy = {}) {
        const modeRaw = policy.mode && String(policy.mode).trim() ? String(policy.mode).trim() : DEFAULT_POLICY_MODE;
        const mode = modeRaw.toLowerCase();
        const containArp = typeof policy.contain_arp === 'boolean' ? policy.contain_arp : DEFAULT_CONTAIN_ARP;
        const containDhcp = typeof policy.contain_dhcp === 'boolean' ? policy.contain_dhcp : DEFAULT_CONTAIN_DHCP;
        return {
            mode,
            contain_arp: containArp,
            contain_dhcp: containDhcp,
            directions: ['src', 'dst']
        };
    }

    normalizeLifecycleOptions(lifecycle = {}) {
        const autoExpire = typeof lifecycle.auto_expire === 'boolean' ? lifecycle.auto_expire : DEFAULT_AUTO_EXPIRE;
        const rawDuration = Number(lifecycle.duration_seconds);
        const durationSeconds = Number.isFinite(rawDuration)
            ? Math.min(Math.max(Math.floor(rawDuration), 0), MAX_DURATION_SECONDS)
            : DEFAULT_DURATION_SECONDS;

        return {
            auto_expire: autoExpire,
            duration_seconds: durationSeconds
        };
    }

    createMitigationId() {
        return `mit-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
    }

    scheduleMitigationExpiry(mitigationId) {
        const mitigation = this.activeMitigations.get(mitigationId);
        if (!mitigation || !mitigation.expires_at) {
            return;
        }

        const expiresAtMillis = Date.parse(mitigation.expires_at);
        if (!Number.isFinite(expiresAtMillis)) {
            return;
        }

        const delay = Math.max(expiresAtMillis - Date.now(), 0);
        this.clearMitigationTimer(mitigationId);

        const timer = setTimeout(async () => {
            try {
                await this.clearMitigationById(mitigationId, {
                    reason: 'auto-expire'
                });
            } catch (error) {
                console.error(`❌ Auto-expire rollback failed for ${mitigationId}:`, error.message);
            }
        }, delay);

        this.expiryTimers.set(mitigationId, timer);
    }

    clearMitigationTimer(mitigationId) {
        const existing = this.expiryTimers.get(mitigationId);
        if (existing) {
            clearTimeout(existing);
            this.expiryTimers.delete(mitigationId);
        }
    }

    getMitigationStatus(record) {
        if (record.status !== 'active') {
            return record.status;
        }
        if (!record.expires_at) {
            return 'active';
        }
        return Date.now() >= Date.parse(record.expires_at) ? 'expired_pending_rollback' : 'active';
    }

    serializeMitigation(record) {
        const expiresAtMillis = record.expires_at ? Date.parse(record.expires_at) : null;
        const remainingSeconds = expiresAtMillis
            ? Math.max(Math.ceil((expiresAtMillis - Date.now()) / 1000), 0)
            : null;
        return {
            ...record,
            status: this.getMitigationStatus(record),
            remaining_seconds: remainingSeconds
        };
    }

    listActiveMitigations() {
        const entries = Array.from(this.activeMitigations.values())
            .filter((entry) => this.getMitigationStatus(entry) === 'active')
            .map((entry) => this.serializeMitigation(entry))
            .sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at));
        return entries;
    }

    registerMitigation({ mitigationId, normalized, policy, lifecycle, localization }) {
        const recordMitigationId = mitigationId || this.createMitigationId();
        const createdAt = new Date().toISOString();
        const expiresAt = lifecycle.auto_expire
            ? new Date(Date.now() + lifecycle.duration_seconds * 1000).toISOString()
            : null;

        const record = {
            mitigation_id: recordMitigationId,
            status: 'active',
            target: normalized,
            policy,
            lifecycle,
            localization,
            created_at: createdAt,
            expires_at: expiresAt,
            rollback: {
                system_owned_only: true,
                last_reason: null,
                completed_at: null
            }
        };

        this.activeMitigations.set(recordMitigationId, record);
        if (expiresAt) {
            this.scheduleMitigationExpiry(recordMitigationId);
        }

        return this.serializeMitigation(record);
    }

    findActiveMitigationByTarget(ip, mac) {
        const normalized = this.normalizeTarget(ip, mac);
        const active = Array.from(this.activeMitigations.values())
            .filter((entry) => entry.status === 'active')
            .sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at));

        return active.find((entry) => {
            if (normalized.ip && entry.target.ip === normalized.ip) {
                return true;
            }
            if (normalized.mac && entry.target.mac === normalized.mac) {
                return true;
            }
            return false;
        }) || null;
    }

    async clearMitigationById(mitigationId, options = {}) {
        const mitigation = this.activeMitigations.get(mitigationId);
        if (!mitigation) {
            throw new Error(`Mitigation ${mitigationId} not found`);
        }
        if (mitigation.status !== 'active') {
            return {
                success: true,
                mitigation: this.serializeMitigation(mitigation),
                message: `Mitigation ${mitigationId} already ${mitigation.status}`
            };
        }

        const reason = options.reason || 'manual-clear';
        const rollbackPayload = {
            mitigation_id: mitigationId,
            rollback_reason: reason,
            rollback_scope: 'system_owned_only',
            rollback_request_source: 'webapp.network_control',
            quarantine_policy: mitigation.policy,
            lifecycle: {
                auto_expire: false,
                duration_seconds: 0
            }
        };

        const publishResult = await this.publishManualWorkflowCommand(
            'REMOVE_MITIGATION',
            mitigation.target.ip,
            mitigation.target.mac,
            'REMOVE_ISOLATION',
            'Rollback mitigation via Network page',
            rollbackPayload
        );

        mitigation.status = reason === 'auto-expire' ? 'expired' : 'cleared';
        mitigation.rollback.last_reason = reason;
        mitigation.rollback.completed_at = new Date().toISOString();
        this.clearMitigationTimer(mitigationId);
        this.activeMitigations.delete(mitigationId);

        return {
            success: true,
            publish: publishResult,
            mitigation: this.serializeMitigation(mitigation)
        };
    }

    async clearMitigation({ mitigationId, ip, mac, reason } = {}) {
        if (mitigationId) {
            return this.clearMitigationById(mitigationId, {
                reason: reason || 'manual-clear'
            });
        }

        const mitigation = this.findActiveMitigationByTarget(ip, mac);
        if (!mitigation) {
            // Fallback path: local cache may be stale/missing for auto-isolated hosts.
            // Publish explicit rollback command by target so ODL module can resolve via its own indexes.
            const normalized = this.normalizeTarget(ip, mac);
            if (!normalized.ip && !normalized.mac) {
                throw new Error('No active mitigation found and no valid target provided');
            }

            // Generate a temporary mitigation_id for traceability.
            // The ODL module will resolve the actual flows via target-based
            // scan when the provided mitigation_id does not match any
            // in-memory or prefix-based record.
            const tempMitigationId = this.createMitigationId();

            const publish = await this.publishManualWorkflowCommand(
                'REMOVE_MITIGATION',
                normalized.ip,
                normalized.mac,
                'REMOVE_ISOLATION',
                'Rollback mitigation via Network page (target fallback)',
                {
                    mitigation_id: tempMitigationId,
                    rollback_reason: reason || 'manual-clear',
                    rollback_scope: 'system_owned_only',
                    rollback_request_source: 'webapp.network_control',
                    lifecycle: {
                        auto_expire: false,
                        duration_seconds: 0
                    }
                }
            );

            return {
                success: true,
                publish,
                mitigation: null,
                message: 'Rollback command queued using target fallback (no local mitigation cache entry)'
            };
        }

        return this.clearMitigationById(mitigation.mitigation_id, {
            reason: reason || 'manual-clear'
        });
    }

    extendMitigation(mitigationId, extendSeconds) {
        const mitigation = this.activeMitigations.get(mitigationId);
        if (!mitigation) {
            throw new Error(`Mitigation ${mitigationId} not found`);
        }
        if (mitigation.status !== 'active') {
            throw new Error(`Mitigation ${mitigationId} is not active`);
        }
        if (!mitigation.lifecycle?.auto_expire) {
            throw new Error(`Mitigation ${mitigationId} is not auto-expiring`);
        }

        const parsedExtend = Number(extendSeconds);
        if (!Number.isFinite(parsedExtend) || parsedExtend <= 0) {
            throw new Error('extend_seconds must be a positive number');
        }

        const currentExpiry = mitigation.expires_at ? Date.parse(mitigation.expires_at) : Date.now();
        const nextExpiry = new Date(currentExpiry + Math.floor(parsedExtend) * 1000).toISOString();
        mitigation.expires_at = nextExpiry;
        mitigation.lifecycle.duration_seconds = Math.min(
            mitigation.lifecycle.duration_seconds + Math.floor(parsedExtend),
            MAX_DURATION_SECONDS
        );
        this.activeMitigations.set(mitigationId, mitigation);
        this.scheduleMitigationExpiry(mitigationId);

        return this.serializeMitigation(mitigation);
    }

    extractHostsFromTopology(topology) {
        const result = [];
        const topologies = topology?.['network-topology']?.topology || [];

        for (const topo of topologies) {
            const nodes = topo?.node || [];
            for (const node of nodes) {
                const nodeId = node?.['node-id'] || '';
                if (!nodeId.startsWith('host:')) {
                    continue;
                }

                const mac = nodeId.replace('host:', '').toLowerCase();
                const addresses = node?.['host-tracker-service:addresses'] || [];
                const ip = addresses?.[0]?.ip || 'Unknown';
                const attachmentPoints = node?.['host-tracker-service:attachment-points'] || [];
                const tpId = attachmentPoints?.[0]?.['tp-id'] || null;

                const localization = this.deriveLocalization(tpId);

                result.push({
                    id: nodeId,
                    mac,
                    ip,
                    attachment: tpId || 'Unknown',
                    localization
                });
            }
        }

        return result;
    }

    deriveLocalization(tpId) {
        if (!tpId || typeof tpId !== 'string') {
            return {
                status: LOCALIZATION_STATUS.PARTIAL,
                confidence: 'low',
                node: null,
                port: null,
                source_of_truth: 'odl.topology',
                reason: 'Host attachment point not present in topology data'
            };
        }

        const segments = tpId.split(':');
        if (segments.length < 3 || !segments[0].startsWith('openflow')) {
            return {
                status: LOCALIZATION_STATUS.PARTIAL,
                confidence: 'low',
                node: null,
                port: tpId,
                source_of_truth: 'odl.topology',
                reason: `Attachment point format not recognized: ${tpId}`
            };
        }

        const port = segments[segments.length - 1];
        const node = segments.slice(0, segments.length - 1).join(':');
        return {
            status: LOCALIZATION_STATUS.RESOLVED,
            confidence: 'high',
            node,
            port,
            source_of_truth: 'odl.topology'
        };
    }

    resolveEndpointLocalization(topology, ip, mac) {
        const hosts = topology?.hosts || this.extractHostsFromTopology(topology);
        const normalized = this.normalizeTarget(ip, mac);

        let matchedHost = null;
        if (normalized.ip) {
            matchedHost = hosts.find((host) => host.ip === normalized.ip);
        }
        if (!matchedHost && normalized.mac) {
            matchedHost = hosts.find((host) => host.mac === normalized.mac);
        }

        if (!matchedHost) {
            return {
                status: LOCALIZATION_STATUS.FALLBACK,
                confidence: 'low',
                node: DEFAULT_NODE,
                port: null,
                source_of_truth: 'default_config',
                fallback_reason: 'Host not found in ODL topology by IP or MAC'
            };
        }

        const derived = matchedHost.localization || this.deriveLocalization(matchedHost.attachment);
        if (derived.status === LOCALIZATION_STATUS.RESOLVED) {
            return derived;
        }

        return {
            status: LOCALIZATION_STATUS.FALLBACK,
            confidence: 'low',
            node: DEFAULT_NODE,
            port: derived.port || null,
            source_of_truth: 'default_config',
            fallback_reason: derived.reason || 'Insufficient attachment details for target host',
            partial_match: {
                host_id: matchedHost.id,
                ip: matchedHost.ip,
                mac: matchedHost.mac
            }
        };
    }

    async publishManualWorkflowCommand(eventType, ip, mac, action, justification, extraPayload = {}) {
        await this.waitForChannel();
        const topology = await this.getTopology();
        const localization = this.resolveEndpointLocalization(topology, ip, mac);
        const normalized = this.normalizeTarget(ip, mac);

        const payload = {
            targetHost: normalized.ip,
            ip_address: normalized.ip,
            mac_address: normalized.mac,
            action,
            priority: 'high',
            sdn_controller: 'opendaylight',
            justification,
            localization,
            ...extraPayload
        };

        const command = this.buildWorkflowMessage(eventType, payload);

        this.channel.sendToQueue(COMMAND_QUEUE, Buffer.from(JSON.stringify(command)));
        console.log(`📤 Published ${eventType} command for ${normalized.ip || normalized.mac}`);

        return {
            status: 'sent',
            success: true,
            command,
            localization
        };
    }

    // Publish isolation command
    async isolateHost(ip, mac, options = {}) {
        const normalized = this.normalizeTarget(ip, mac);
        const policy = this.normalizePolicyOptions(options.policy || {});
        const lifecycle = this.normalizeLifecycleOptions(options.lifecycle || {});
        const mitigationId = this.createMitigationId();

        const publishResult = await this.publishManualWorkflowCommand(
            'INITIATE_MITIGATION',
            normalized.ip,
            normalized.mac,
            'ISOLATE_VLAN',
            'Manual isolation via web Network page',
            {
                mitigation_id: mitigationId,
                quarantine_policy: policy,
                lifecycle,
                rollback_scope: 'system_owned_only',
                rollback_request_source: 'webapp.network_control'
            }
        );

        const mitigation = this.registerMitigation({
            mitigationId,
            normalized,
            policy,
            lifecycle,
            localization: publishResult.localization
        });

        return {
            ...publishResult,
            mitigation
        };
    }

    // Publish remove isolation command
    async removeIsolation(ip, mac, options = {}) {
        return this.clearMitigation({
            mitigationId: options.mitigation_id,
            ip,
            mac,
            reason: options.reason || 'manual-clear'
        });
    }

    // Publish topology discovery (scan) command
    async triggerNetworkScan(startIp = null) {
        await this.waitForChannel();

        const payload = {
            type: 'ping_sweep',
            target: 'all',
            start_ip: startIp || null,
            action: 'TOPOLOGY_DISCOVER',
            source_of_truth: 'odl.topology'
        };

        const command = this.buildWorkflowMessage('ODL_TOPOLOGY_DISCOVER', payload);

        this.channel.sendToQueue(COMMAND_QUEUE, Buffer.from(JSON.stringify(command)));
        console.log(`📤 Published network scan command${startIp ? ' for ' + startIp : ''}`);
        return { status: 'sent', command };
    }

    getSimulatedTopology() {
        return {
            "network-topology": {
                "topology": [
                    {
                        "topology-id": "flow:1",
                        "node": [
                            { "node-id": "openflow:1" },
                            { "node-id": "openflow:2" },
                            { "node-id": "host:00:00:00:00:00:01", "host-tracker-service:attachment-points": [{ "tp-id": "openflow:1:1" }] },
                            { "node-id": "host:00:00:00:00:00:02", "host-tracker-service:attachment-points": [{ "tp-id": "openflow:2:1" }] },
                            { "node-id": "host:aa:bb:cc:dd:ee:ff", "host-tracker-service:attachment-points": [{ "tp-id": "openflow:1:2" }] }
                        ],
                        "link": [
                            { "link-id": "link1", "source": { "source-node": "openflow:1" }, "destination": { "dest-node": "openflow:2" } }
                        ]
                    }
                ]
            }
        };
    }

    readScanResults() {
        try {
            if (fs.existsSync(SCAN_RESULTS_PATH)) {
                const raw = fs.readFileSync(SCAN_RESULTS_PATH, 'utf-8');
                const parsed = JSON.parse(raw);
                if (parsed && Array.isArray(parsed.hosts)) {
                    return parsed;
                }
            }
        } catch (err) {
            console.warn('⚠️ Failed to read scan results:', err.message);
        }
        return null;
    }
}

module.exports = new OdlService();
