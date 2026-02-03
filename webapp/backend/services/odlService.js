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
        await this.waitForChannel();

        const command = {
            message_type: 'workflow_command',
            event_id: `manual-${Date.now()}`,
            timestamp: new Date().toISOString(),
            event_type: 'INITIATE_MITIGATION',
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
            event_type: 'manual.scan',
            source_module: 'Webapp',
            payload: payload
        };

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
