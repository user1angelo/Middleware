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
const DEFAULT_NODE = process.env.ODL_DEFAULT_NODE || 'openflow:1';

const LOCALIZATION_STATUS = {
    RESOLVED: 'resolved',
    PARTIAL: 'partial',
    FALLBACK: 'fallback'
};

class OdlService {
    constructor() {
        this.connection = null;
        this.channel = null;
        this.connecting = false;
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
        const normalizedIp = ip && String(ip).trim() ? String(ip).trim() : null;
        const normalizedMac = mac && String(mac).trim() ? String(mac).trim().toLowerCase() : null;
        return { ip: normalizedIp, mac: normalizedMac };
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

    async publishManualWorkflowCommand(eventType, ip, mac, action, justification) {
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
            localization
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
    async isolateHost(ip, mac) {
        return this.publishManualWorkflowCommand(
            'INITIATE_MITIGATION',
            ip,
            mac,
            'ISOLATE_VLAN',
            'Manual isolation via web Network page'
        );
    }

    // Publish remove isolation command
    async removeIsolation(ip, mac) {
        return this.publishManualWorkflowCommand(
            'REMOVE_MITIGATION',
            ip,
            mac,
            'REMOVE_ISOLATION',
            'Manual remove isolation via web Network page'
        );
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
}

module.exports = new OdlService();
