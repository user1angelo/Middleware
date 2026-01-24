const amqp = require('amqplib');
const http = require('http');
const https = require('https');

let io = null;
let rabbitConnection = null;
let rabbitChannel = null;
let statsInterval = null;

// Feature flags and config (safe defaults)
const ENABLE_RABBITMQ_LOG_TAP = (process.env.ENABLE_RABBITMQ_LOG_TAP || 'false').toLowerCase() === 'true';
const ENABLE_RABBITMQ_STATS = (process.env.ENABLE_RABBITMQ_STATS || 'true').toLowerCase() !== 'false';
const RABBITMQ_MGMT_HOST = process.env.RABBITMQ_MGMT_HOST || process.env.RABBITMQ_HOST || '127.0.0.1';
const RABBITMQ_MGMT_PORT = Number(process.env.RABBITMQ_MGMT_PORT || 15672);
const RABBITMQ_MGMT_USER = process.env.RABBITMQ_MGMT_USER || process.env.RABBITMQ_USER || 'guest';
const RABBITMQ_MGMT_PASSWORD = process.env.RABBITMQ_MGMT_PASSWORD || process.env.RABBITMQ_PASSWORD || 'guest';
const RABBITMQ_VHOST = process.env.RABBITMQ_VHOST || '/';
const RABBITMQ_STATS_INTERVAL_MS = Number(process.env.RABBITMQ_STATS_INTERVAL_MS || 3000);
const RABBITMQ_MGMT_PROTOCOL = (process.env.RABBITMQ_MGMT_PROTOCOL || 'http').toLowerCase();

// Initialize with Socket.IO instance
function initialize(socketIo) {
  io = socketIo;

  if (ENABLE_RABBITMQ_LOG_TAP) {
    // Connect to RabbitMQ for message content monitoring (optional, can consume messages)
    connectToRabbitMQ().catch(err => {
      console.error('Failed to connect to RabbitMQ for logging:', err);
    });

    // Set up periodic reconnection attempts
    setInterval(() => {
      if (!rabbitConnection || !rabbitChannel) {
        connectToRabbitMQ().catch(err => {
          console.error('RabbitMQ reconnection failed:', err);
        });
      }
    }, 30000); // Try every 30 seconds
  } else {
    console.log('ℹ️ RabbitMQ log tap is disabled (ENABLE_RABBITMQ_LOG_TAP=false)');
  }

  if (ENABLE_RABBITMQ_STATS) {
    startRabbitMQStatsPolling();
  } else {
    console.log('ℹ️ RabbitMQ stats polling disabled (ENABLE_RABBITMQ_STATS=false)');
  }
}

// Connect to RabbitMQ
async function connectToRabbitMQ() {
  try {
    const rabbitUrl = `amqp://${process.env.RABBITMQ_USER}:${process.env.RABBITMQ_PASSWORD}@${process.env.RABBITMQ_HOST}:${process.env.RABBITMQ_PORT}`;

    rabbitConnection = await amqp.connect(rabbitUrl);
    rabbitChannel = await rabbitConnection.createChannel();

    rabbitConnection.on('error', (e) => console.error('RabbitMQ connection error:', e.message));
    rabbitConnection.on('close', () => console.warn('RabbitMQ connection closed'));

    console.log('✅ Connected to RabbitMQ for log monitoring');

    // Listen to relevant queues for logging
    const queues = [
      'alerts_queue',
      'workflow_queue',
      'query_response_queue',
      'workflow_response_queue'
    ];

    for (const queueName of queues) {
      try {
        await rabbitChannel.assertQueue(queueName, { durable: true });

        // IMPORTANT: Use noAck to avoid requeue loops. This will CONSUME messages.
        // Only enable this feature in non-production or with dedicated mirror queues.
        rabbitChannel.consume(
          queueName,
          (msg) => {
            if (msg) {
              try {
                const content = msg.content.toString();
                let message = null;
                try {
                  message = JSON.parse(content);
                } catch (_) {
                  message = content;
                }
                // Broadcast to dashboard
                broadcastRabbitMQLog(queueName, message);
                // With noAck=true, the broker auto-acks.
              } catch (err) {
                console.error(`Error processing message from ${queueName}:`, err);
              }
            }
          },
          {
            noAck: true,
            // Lower consumer priority so real consumers (default priority 0) are preferred
            arguments: { 'x-priority': -1 },
            consumerTag: 'middleware-dashboard-log-tap'
          }
        );

        console.log(`  📡 Monitoring queue: ${queueName}`);
      } catch (err) {
        console.error(`Failed to monitor queue ${queueName}:`, err);
      }
    }
  } catch (error) {
    console.error('RabbitMQ connection error:', error.message);
    rabbitConnection = null;
    rabbitChannel = null;
    throw error;
  }
}

// Broadcast process logs to clients
function broadcastLog(processKey, message) {
  if (io) {
    const logData = {
      process: processKey,
      message: message,
      timestamp: new Date().toISOString()
    };
    console.log(`✉️  Broadcasting log for '${processKey}' (${message.length} bytes) to ${io.engine.clientsCount} client(s)`);
    io.emit('process-log', logData);
  } else {
    console.error('❌ Socket.IO not initialized - cannot broadcast logs!');
  }
}

// Broadcast RabbitMQ messages as logs
function broadcastRabbitMQLog(queueName, message) {
  if (io) {
    io.emit('rabbitmq-log', {
      queue: queueName,
      message: message,
      timestamp: new Date().toISOString()
    });
  }
}

// Broadcast RabbitMQ queue stats (non-disruptive monitoring)
function broadcastRabbitMQStats(queueStats) {
  if (!io) return;
  const now = new Date().toISOString();
  for (const s of queueStats) {
    io.emit('rabbitmq-stats', {
      queue: s.name,
      vhost: s.vhost,
      messages: s.messages,
      messages_ready: s.messages_ready,
      messages_unacknowledged: s.messages_unacknowledged,
      incoming_rate: s.message_stats?.publish_details?.rate || 0,
      deliver_get_rate: s.message_stats?.deliver_get_details?.rate || 0,
      timestamp: now
    });
  }
}

// Periodically poll RabbitMQ management API for queue stats
function startRabbitMQStatsPolling() {
  const client = RABBITMQ_MGMT_PROTOCOL === 'https' ? https : http;
  const vhostPath = encodeURIComponent(RABBITMQ_VHOST);
  const path = `/api/queues/${vhostPath}`;

  const doPoll = () => {
    const options = {
      hostname: RABBITMQ_MGMT_HOST,
      port: RABBITMQ_MGMT_PORT,
      path,
      method: 'GET',
      auth: `${RABBITMQ_MGMT_USER}:${RABBITMQ_MGMT_PASSWORD}`,
      headers: { 'Accept': 'application/json' }
    };

    const req = client.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        try {
          const allQueues = JSON.parse(data);
          // Only include queues we care about
          const targetQueues = [
            'alerts_queue',
            'workflow_queue',
            'query_response_queue',
            'workflow_response_queue'
          ];
          const filtered = allQueues.filter(q => targetQueues.includes(q.name));
          broadcastRabbitMQStats(filtered);
        } catch (e) {
          // Swallow to avoid noisy logs
        }
      });
    });

    req.on('error', () => { /* ignore transient errors */ });
    req.end();
  };

  // Kick off immediately then repeat
  doPoll();
  statsInterval = setInterval(doPoll, RABBITMQ_STATS_INTERVAL_MS);
}

// Cleanup
async function cleanup() {
  if (statsInterval) {
    clearInterval(statsInterval);
    statsInterval = null;
  }
  if (rabbitChannel) {
    await rabbitChannel.close();
  }
  if (rabbitConnection) {
    await rabbitConnection.close();
  }
}

module.exports = {
  initialize,
  broadcastLog,
  broadcastRabbitMQLog,
  cleanup
};
