const http = require('http');
const amqp = require('amqplib');
require('dotenv').config();

// Configuration
const ODL_HOST = process.env.ODL_HOST || 'localhost';
const ODL_PORT = process.env.ODL_PORT || 8181;
const ODL_USER = process.env.ODL_USER || 'admin';
const ODL_PASSWORD = process.env.ODL_PASSWORD || 'admin';

const RABBITMQ_HOST = process.env.RABBITMQ_HOST || 'localhost';
const RABBITMQ_PORT = process.env.RABBITMQ_PORT || 5672;
const RABBITMQ_USER = process.env.RABBITMQ_USER || 'guest';
const RABBITMQ_PASS = process.env.RABBITMQ_PASS || 'guest';
const COMMAND_QUEUE = 'workflow_command_queue';

class OdlService {
    constructor() {
        this.connection = null;
        this.channel = null;
        this.connectRabbitMQ();
    }

    async connectRabbitMQ() {
        try {
            const url = `amqp://${RABBITMQ_USER}:${RABBITMQ_PASS}@${RABBITMQ_HOST}:${RABBITMQ_PORT}`;
            this.connection = await amqp.connect(url);
            this.channel = await this.connection.createChannel();
            await this.channel.assertQueue(COMMAND_QUEUE, { durable: true });
            console.log('✅ OdlService connected to RabbitMQ');
        } catch (error) {
            console.error('❌ OdlService RabbitMQ connection error:', error.message);
            // Retry logic could be added here
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
    async isolateHost(ip, mac) {
        if (!this.channel) {
            throw new Error('RabbitMQ channel not ready');
        }

        const command = {
            message_type: 'odl.host.isolate',
            event_id: `manual-${Date.now()}`,
            timestamp: new Date().toISOString(),
            event_type: 'manual.isolation',
            source_module: 'Webapp',
            payload: {
                ip_address: ip,
                mac_address: mac,
                reason: 'Manual isolation via Web Dashboard'
            }
        };

        this.channel.sendToQueue(COMMAND_QUEUE, Buffer.from(JSON.stringify(command)));
        console.log(`📤 Published manual isolation command for ${ip || mac}`);
        return { status: 'sent', command };
    }

    // Publish topology discovery (scan) command
    async triggerNetworkScan() {
        if (!this.channel) {
            throw new Error('RabbitMQ channel not ready');
        }

        const command = {
            message_type: 'odl.topology.discover',
            event_id: `scan-${Date.now()}`,
            timestamp: new Date().toISOString(),
            event_type: 'manual.scan',
            source_module: 'Webapp',
            payload: {
                type: 'ping_sweep',
                target: 'all'
            }
        };

        this.channel.sendToQueue(COMMAND_QUEUE, Buffer.from(JSON.stringify(command)));
        console.log(`📤 Published network scan command`);
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
