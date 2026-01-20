const express = require('express');
const http = require('http');
const socketIo = require('socket.io');
const cors = require('cors');
const path = require('path');
const fs = require('fs').promises;
require('dotenv').config();

const processManager = require('./services/processManager');
const configService = require('./services/configService');
const workflowService = require('./services/workflowService');
const moduleHealthService = require('./services/moduleHealthService');
const logService = require('./services/logService');
const udmConfigService = require('./services/udmConfigService');

const app = express();
const server = http.createServer(app);
const io = socketIo(server, {
  cors: {
    origin: "http://localhost:3000",
    methods: ["GET", "POST"]
  }
});

// Middleware
app.use(cors());
app.use(express.json());

// Initialize services
logService.initialize(io);
processManager.setLogService(logService);

// ===== API Routes =====

// Health check
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// ===== Process Management Routes =====
app.get('/api/processes/status', async (req, res) => {
  try {
    const status = processManager.getAllStatus();
    res.json(status);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/processes/:name/start', async (req, res) => {
  try {
    const result = await processManager.startProcess(req.params.name);
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/processes/:name/stop', async (req, res) => {
  try {
    const result = await processManager.stopProcess(req.params.name);
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/processes/test', async (req, res) => {
  try {
    const result = await processManager.runTest();
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// OpenDaylight Module demo: launches a headful terminal showing evidence
app.post('/api/testing/opendaylight-demo', async (req, res) => {
  try {
    const result = await processManager.runOpenDaylightDemo();
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// ===== Configuration Routes =====
app.get('/api/config/:program', async (req, res) => {
  try {
    const config = await configService.getConfig(req.params.program);
    res.json(config);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.put('/api/config/:program', async (req, res) => {
  try {
    const result = await configService.updateConfig(req.params.program, req.body.content);
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// ===== Workflow Routes =====
app.get('/api/workflows', async (req, res) => {
  try {
    const workflows = await workflowService.listWorkflows();
    res.json(workflows);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.get('/api/workflows/:category/:name', async (req, res) => {
  try {
    const workflow = await workflowService.getWorkflow(req.params.category, req.params.name);
    res.json(workflow);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/workflows/:category', async (req, res) => {
  try {
    const result = await workflowService.createWorkflow(req.params.category, req.body.name, req.body.content);
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.put('/api/workflows/:category/:name', async (req, res) => {
  try {
    const result = await workflowService.updateWorkflow(req.params.category, req.params.name, req.body.content);
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.delete('/api/workflows/:category/:name', async (req, res) => {
  try {
    const result = await workflowService.deleteWorkflow(req.params.category, req.params.name);
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// ===== Module Health Routes =====
app.get('/api/modules/health', async (req, res) => {
  try {
    const modules = await moduleHealthService.getModuleHealth();
    res.json(modules);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// ===== UDM Config Routes =====
app.get('/api/udm-configs', async (req, res) => {
  try {
    const result = await udmConfigService.listConfigs();
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.get('/api/udm-configs/:id', async (req, res) => {
  try {
    const result = await udmConfigService.getConfig(req.params.id);
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.put('/api/udm-configs/:id', async (req, res) => {
  try {
    const result = await udmConfigService.updateConfig(req.params.id, req.body.content);
    res.json(result);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// ===== WebSocket Connection =====
io.on('connection', (socket) => {
  console.log('✅ Client connected:', socket.id);
  
  // Send a test message immediately
  socket.emit('process-log', {
    process: 'threatContextStore',
    message: '[Dashboard] WebSocket connection established\n',
    timestamp: new Date().toISOString()
  });
  
  socket.on('disconnect', () => {
    console.log('❌ Client disconnected:', socket.id);
  });
});

// Start server
const PORT = process.env.PORT || 3001;
server.listen(PORT, () => {
  console.log(`
╔════════════════════════════════════════════════╗
║  Middleware Dashboard Backend v1.0            ║
╚════════════════════════════════════════════════╝

🚀 Server running on port ${PORT}
📡 WebSocket ready for real-time logs
🔗 Frontend: http://localhost:3000
🔗 API: http://localhost:${PORT}/api

  `);
});

// Graceful shutdown
process.on('SIGINT', async () => {
  console.log('\n🛑 Shutting down gracefully...');
  await processManager.stopAll();
  process.exit(0);
});
