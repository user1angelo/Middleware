const http = require('http');
const amqp = require('amqplib');
require('dotenv').config();

// Configuration
const ODL_HOST = process.env.ODL_HOST || 'localhost';
const ODL_PORT = process.env.ODL_PORT || 8181;
const ODL_USER = process.env.ODL_USER || 'admin';
const ODL_PASSWORD = process.env.ODL_PASSWORD || 'admin';

const RABBITMQ_HOST = process.env.RABBITMQ_HOST || '127.0.0.1';
const RABBITMQ_PORT = process.env.RABBITMQ_PORT || 5672;
const RABBITMQ_USER = process.env.RABBITMQ_USER || 'user';
const RABBITMQ_PASS = process.env.RABBITMQ_PASSWORD || 'password';
const COMMAND_QUEUE = 'workflow_response_queue';
const DEFAULT_ODL_NODE = process.env.ODL_DEFAULT_NODE || 'openflow:1';
const DEFAULT_CONTAIN_ARP = process.env.QUARANTINE_CONTAIN_ARP === 'true';
const DEFAULT_CONTAIN_DHCP = process.env.QUARANTINE_CONTAIN_DHCP === 'true';
const DEFAULT_POLICY_MODE = process.env.QUARANTINE_POLICY_MODE || 'strict';

class OdlService {
    constructor() {
        this.connection = null;
        this.channel = null;
        this.connecting = false;
        this.activeMitigations = new Map();
        this.expiryTimers = new Map();
        this.connectRabbitMQ();
    }

    createEnvelope(eventType, payload, eventIdPrefix = 'manual') {
        return {
            message_type: 'workflow_command',
            event_id: `${eventIdPrefix}-${Date.now()}`,
            timestamp: new Date().toISOString(),
            event_type: eventType,
            source_module: 'Webapp',
            payload
        };
    }

    normalizeDurationMs(durationMs) {
        const parsed = Number(durationMs);
        if (!Number.isFinite(parsed) || parsed <= 0) {
            return 0;
        }
        return Math.floor(parsed);
    }

    parseManagementPorts(portsInput) {
        if (Array.isArray(portsInput)) {
            return portsInput
                .map((port) => Number(port))
                .filter((port) => Number.isInteger(port) && port > 0 && port <= 65535);
        }

        if (typeof portsInput === 'string') {
            return portsInput
                .split(',')
                .map((part) => Number(part.trim()))
                .filter((port) => Number.isInteger(port) && port > 0 && port <= 65535);
        }

        return [];
    }

    normalizePolicy(options = {}) {
        const policy = options.policy || {};
        const managementHost = (policy.management_host || options.management_host || '').trim();
        const managementPorts = this.parseManagementPorts(policy.management_ports ?? options.management_ports);
        const containArp = policy.contain_arp ?? options.contain_arp ?? DEFAULT_CONTAIN_ARP;
        const containDhcp = policy.contain_dhcp ?? options.contain_dhcp ?? DEFAULT_CONTAIN_DHCP;
        const policyMode = options.policy_mode || policy.policy_mode || DEFAULT_POLICY_MODE;

        return {
            policy_mode: policyMode,
            allowlist_profile: options.allowlist_profile || policy.allowlist_profile || 'custom',
            management_host: managementHost,
            management_ports: managementPorts,
            contain_arp: Boolean(containArp),
            contain_dhcp: Boolean(containDhcp)
        };
    }

    extractHosts(topology) {
        if (!topology || !topology['network-topology'] || !Array.isArray(topology['network-topology'].topology)) {
            return [];
        }

        const hosts = [];
        for (const topo of topology['network-topology'].topology) {
            for (const node of topo.node || []) {
                const nodeId = node['node-id'] || '';
                if (!nodeId.startsWith('host:')) {
                    continue;
                }

                const mac = nodeId.replace('host:', '').toLowerCase();
                const addresses = node['host-tracker-service:addresses'] || [];
                const attachment = (node['host-tracker-service:attachment-points'] || [])[0] || {};
                const tpId = attachment['tp-id'] || null;
                const resolvedNode = tpId ? tpId.split(':').slice(0, 2).join(':') : null;
                const ip = addresses[0]?.ip || null;

                let status = 'fallback';
                if (resolvedNode && tpId) {
                    status = 'resolved';
                } else if (ip || mac) {
                    status = 'partial';
                }

                hosts.push({
                    id: nodeId,
                    ip,
                    mac,
                    node: resolvedNode,
                    port: tpId,
                    status,
                    source_of_truth: 'odl-host-tracker'
                });
            }
        }

        return hosts;
    }

    resolveLocalization(ip, mac, topology) {
        const targetIp = (ip || '').trim();
        const targetMac = (mac || '').trim().toLowerCase();
        const hosts = this.extractHosts(topology);

        const matched = hosts.find((host) => {
            const byIp = targetIp && host.ip === targetIp;
            const byMac = targetMac && host.mac === targetMac;
            return byIp || byMac;
        });

        if (matched && matched.node && matched.port) {
            return {
                status: 'resolved',
                node: matched.node,
                port: matched.port,
                source_of_truth: matched.source_of_truth,
                fallback_reason: null
            };
        }

        if (matched) {
            return {
                status: 'partial',
                node: matched.node || DEFAULT_ODL_NODE,
                port: matched.port || null,
                source_of_truth: matched.source_of_truth,
                fallback_reason: matched.node ? 'missing-port' : 'missing-node-and-port'
            };
        }

        return {
            status: 'fallback',
            node: DEFAULT_ODL_NODE,
            port: null,
            source_of_truth: 'default-node',
            fallback_reason: 'host-not-found-in-topology'
        };
    }

    async getTopologyView() {
        const topology = await this.getTopology();
        const hosts = this.extractHosts(topology);
        return {
            raw_topology: topology,
            hosts,
            summary: {
                total_hosts: hosts.length,
                resolved: hosts.filter((host) => host.status === 'resolved').length,
                partial: hosts.filter((host) => host.status === 'partial').length,
                fallback: hosts.filter((host) => host.status === 'fallback').length
            }
        };
    }

    scheduleAutoExpiry(mitigationId) {
        const mitigation = this.activeMitigations.get(mitigationId);
        if (!mitigation || !mitigation.auto_expire || !mitigation.expires_at) {
            return;
        }

        if (this.expiryTimers.has(mitigationId)) {
            clearTimeout(this.expiryTimers.get(mitigationId));
            this.expiryTimers.delete(mitigationId);
        }

        const remainingMs = new Date(mitigation.expires_at).getTime() - Date.now();
        if (remainingMs <= 0) {
            this.clearMitigation(mitigationId, {
                reason: 'auto-expired',
                rollback_note: 'Auto-expired by lifecycle policy'
            }).catch((error) => {
                console.error(`❌ Failed to auto-clear mitigation ${mitigationId}:`, error.message);
            });
            return;
        }

        const timer = setTimeout(() => {
            this.clearMitigation(mitigationId, {
                reason: 'auto-expired',
                rollback_note: 'Auto-expired by lifecycle policy'
            }).catch((error) => {
                console.error(`❌ Failed to auto-clear mitigation ${mitigationId}:`, error.message);
            });
        }, remainingMs);

        this.expiryTimers.set(mitigationId, timer);
    }

    getMitigationSnapshot(mitigation) {
        return {
            mitigation_id: mitigation.mitigation_id,
            ip: mitigation.ip,
            mac: mitigation.mac,
            status: mitigation.status,
            created_at: mitigation.created_at,
            updated_at: mitigation.updated_at,
            expires_at: mitigation.expires_at,
            auto_expire: mitigation.auto_expire,
            duration_ms: mitigation.duration_ms,
            rollback_note: mitigation.rollback_note,
            owner: mitigation.owner,
            last_command_event_id: mitigation.last_command_event_id,
            clear_reason: mitigation.clear_reason || null
        };
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
                console.log('✅ OdlService connected to RabbitMQ');

                // Handle connection close/error events
                this.connection.on('close', () => {
                    console.warn('⚠️ RabbitMQ connection closed, reconnecting...');
                    this.channel = null;
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

    // Fetch topology from ODL Restconf
    async getTopology() {
        return new Promise((resolve, reject) => {
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
    }

    // Publish isolation command
    async isolateHost(ip, mac, options = {}) {
        await this.waitForChannel();

        const mitigationId = options.mitigation_id || `mit-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        const autoExpire = options.auto_expire !== false;
        const durationMs = this.normalizeDurationMs(options.duration_ms);
        const createdAt = new Date().toISOString();
        const expiresAt = autoExpire && durationMs > 0 ? new Date(Date.now() + durationMs).toISOString() : null;
        const rollbackNote = options.rollback_note || 'Manual rollback from web dashboard';
        const policy = this.normalizePolicy(options);
        const topology = await this.getTopology();
        const localization = this.resolveLocalization(ip, mac, topology);

        console.log(
            `[ODL isolate] ${ip || mac} policy=${policy.policy_mode} node=${localization.node} port=${localization.port || 'n/a'} status=${localization.status}`
        );

        const payload = {
            targetHost: ip,
            targetMac: mac,
            action: 'ISOLATE_VLAN',
            priority: 'high',
            sdn_controller: 'opendaylight',
            justification: 'Manual isolation via Web Dashboard',
            mitigation_id: mitigationId,
            owner: 'webapp',
            auto_expire: autoExpire,
            duration_ms: durationMs,
            rollback_note: rollbackNote,
            policy_mode: policy.policy_mode,
            allowlist_profile: policy.allowlist_profile,
            isolation_policy: policy,
            localization,
            lifecycle: {
                auto_expire: autoExpire,
                duration_ms: durationMs,
                rollback_note: rollbackNote,
                created_at: createdAt,
                expires_at: expiresAt
            }
        };

        const command = this.createEnvelope('INITIATE_MITIGATION', payload, 'manual');

        this.channel.sendToQueue(COMMAND_QUEUE, Buffer.from(JSON.stringify(command)));
        console.log(`📤 Published manual isolation command for ${ip || mac} (mitigation_id=${mitigationId})`);

        this.activeMitigations.set(mitigationId, {
            mitigation_id: mitigationId,
            ip: ip || '',
            mac: mac || '',
            status: 'active',
            created_at: createdAt,
            updated_at: createdAt,
            expires_at: expiresAt,
            auto_expire: autoExpire,
            duration_ms: durationMs,
            rollback_note: rollbackNote,
            owner: 'webapp',
            last_command_event_id: command.event_id
        });

        this.scheduleAutoExpiry(mitigationId);

        return {
            status: 'sent',
            mitigation_id: mitigationId,
            localization,
            effective_policy: policy,
            command
        };
    }

    // Publish remove isolation command
    async removeIsolation(ip, mac, options = {}) {
        await this.waitForChannel();

        const mitigationId = options.mitigation_id || null;
        const rollbackNote = options.rollback_note || 'Manual clear from web dashboard';

        const payload = {
            targetHost: ip,
            targetMac: mac,
            action: 'REMOVE_ISOLATION',
            priority: 'high',
            sdn_controller: 'opendaylight',
            justification: 'Manual removal of isolation via Web Dashboard',
            mitigation_id: mitigationId,
            owner: 'webapp',
            rollback_note: rollbackNote
        };

        const command = this.createEnvelope('REMOVE_MITIGATION', payload, 'manual-remove');

        this.channel.sendToQueue(COMMAND_QUEUE, Buffer.from(JSON.stringify(command)));
        console.log(`📤 Published manual remove isolation command for ${ip || mac} (mitigation_id=${mitigationId || 'n/a'})`);

        if (mitigationId && this.activeMitigations.has(mitigationId)) {
            const existing = this.activeMitigations.get(mitigationId);
            const now = new Date().toISOString();
            existing.status = 'clearing';
            existing.updated_at = now;
            existing.last_command_event_id = command.event_id;
            existing.clear_reason = rollbackNote;
            this.activeMitigations.set(mitigationId, existing);
        }

        return { status: 'sent', command };
    }

    // Publish topology discovery (scan) command
    async triggerNetworkScan(startIp = null) {
        await this.waitForChannel();

        const payload = {
            type: 'ping_sweep',
            target: 'all'
        };

        if (startIp) {
            payload.start_ip = startIp;
        }

        const command = {
            message_type: 'odl.topology.discover',
            event_id: `scan-${Date.now()}`,
            timestamp: new Date().toISOString(),
            event_type: 'ODL_TOPOLOGY_DISCOVER',
            source_module: 'Webapp',
            payload: payload
        };

        this.channel.sendToQueue(COMMAND_QUEUE, Buffer.from(JSON.stringify(command)));
        console.log(`📤 Published network scan command${startIp ? ' for ' + startIp : ''}`);
        return { status: 'sent', command };
    }

    listMitigations() {
        const mitigations = Array.from(this.activeMitigations.values())
            .filter((item) => item.status === 'active' || item.status === 'clearing')
            .map((item) => this.getMitigationSnapshot(item))
            .sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());

        return {
            success: true,
            mitigations
        };
    }

    async clearMitigation(mitigationId, options = {}) {
        const mitigation = this.activeMitigations.get(mitigationId);
        if (!mitigation) {
            throw new Error(`Unknown mitigation_id: ${mitigationId}`);
        }

        const reason = options.reason || 'manual-clear';
        const rollbackNote = options.rollback_note || mitigation.rollback_note || 'Manual clear';

        await this.removeIsolation(mitigation.ip, mitigation.mac, {
            mitigation_id: mitigationId,
            rollback_note: rollbackNote
        });

        mitigation.status = 'cleared';
        mitigation.updated_at = new Date().toISOString();
        mitigation.clear_reason = reason;
        this.activeMitigations.set(mitigationId, mitigation);

        if (this.expiryTimers.has(mitigationId)) {
            clearTimeout(this.expiryTimers.get(mitigationId));
            this.expiryTimers.delete(mitigationId);
        }

        return {
            success: true,
            mitigation: this.getMitigationSnapshot(mitigation)
        };
    }

    async extendMitigation(mitigationId, durationMs) {
        const mitigation = this.activeMitigations.get(mitigationId);
        if (!mitigation) {
            throw new Error(`Unknown mitigation_id: ${mitigationId}`);
        }
        if (!mitigation.auto_expire) {
            throw new Error(`Mitigation ${mitigationId} is not auto-expiring`);
        }

        const extraMs = this.normalizeDurationMs(durationMs);
        if (extraMs <= 0) {
            throw new Error('duration_ms must be a positive number');
        }

        const currentExpiry = mitigation.expires_at ? new Date(mitigation.expires_at).getTime() : Date.now();
        const baseTime = Math.max(currentExpiry, Date.now());
        const newExpiryMs = baseTime + extraMs;

        mitigation.expires_at = new Date(newExpiryMs).toISOString();
        mitigation.duration_ms = (mitigation.duration_ms || 0) + extraMs;
        mitigation.updated_at = new Date().toISOString();

        this.activeMitigations.set(mitigationId, mitigation);
        this.scheduleAutoExpiry(mitigationId);

        return {
            success: true,
            mitigation: this.getMitigationSnapshot(mitigation)
        };
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
}

module.exports = new OdlService();
