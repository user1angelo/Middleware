# ModuleRegistry & Lifecycle Manager

**Central hub for User-Defined Module (UDM) management in the SOAR framework**

---

## Overview

The ModuleRegistryAndLifecycleManager acts as a **broker** between User-Defined Modules (UDMs) and the rest of the SOAR system (ThreatContextStore, WorkflowEngine). It handles:

- **Module Registration**: UDMs register their capabilities and endpoints
- **Alert Broadcasting**: Receives alerts from UDMs and broadcasts to both `alerts_queue` and `workflow_queue`
- **Command Routing**: Routes workflow commands from WorkflowEngine to appropriate UDMs
- **Health Monitoring**: Tracks UDM heartbeats and marks modules offline when unresponsive
- **Fault Tolerance**: System continues working even if some modules are offline

---

## Architecture

```
UDM (User-Defined Module)
    ↓ (sends registration, heartbeats, alerts)
workflow_queue
    ↓
ModuleRegistry (AlertBroadcastListener)
    ↓ (broadcasts)
    ├─→ alerts_queue (ThreatContextStore)
    └─→ workflow_queue (WorkflowEngine)

WorkflowEngine
    ↓ (sends commands)
workflow_response_queue
    ↓
ModuleRegistry (CommandRoutingListener)
    ↓ (routes to specific UDM)
UDM command_queue (e.g., sdn_commands_queue)
```

---

## Components

### 1. **ModuleRegistryMain.java**
Main entry point that runs all components in separate threads.

### 2. **ModuleRegistry.java**
- In-memory registry for fast lookups
- PostgreSQL persistence for durability
- Tracks module capabilities, status, and heartbeats

### 3. **AlertBroadcastListener.java**
- Listens to `workflow_queue` for messages from UDMs
- Handles: `registration`, `heartbeat`, `connection_status`, `alert`
- Broadcasts alerts to both system queues

### 4. **CommandRoutingListener.java**
- Listens to `workflow_response_queue` for commands from WorkflowEngine
- Routes commands to appropriate UDM based on capability
- Handles offline module detection

### 5. **HealthMonitor.java**
- Periodic health checks (every 30 seconds)
- Marks modules offline if no heartbeat for 120 seconds
- Automatic recovery when heartbeats resume

### 6. **ConfigLoader.java**
- Centralized configuration management
- Loads from `config.properties` with sensible defaults

---

## Database Schema

```sql
CREATE TABLE registered_modules (
    module_id VARCHAR(255) PRIMARY KEY,
    module_name VARCHAR(255) NOT NULL,
    module_type VARCHAR(100) NOT NULL,
    capabilities JSONB NOT NULL,
    command_queue VARCHAR(255) NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    last_heartbeat TIMESTAMPTZ,
    status VARCHAR(50) DEFAULT 'offline',
    metadata JSONB
);
```

**Installation:**
```bash
psql -U postgres -f schema_registered_modules.sql
```

---

## Message Types

### 1. Registration Message
```json
{
  "message_type": "registration",
  "event_id": "reg-uuid",
  "timestamp": "2025-10-07T22:00:00+08:00",
  "event_type": "system.module.registration",
  "source_module": "WazuhModule",
  "payload": {
    "module_id": "wazuh_udm_01",
    "module_name": "Wazuh Security Module",
    "module_type": "security_monitoring",
    "capabilities": ["wazuh_monitoring", "alert_generation"],
    "command_queue": "wazuh_commands_queue",
    "version": "1.0.0",
    "metadata": {}
  }
}
```

### 2. Heartbeat Message
```json
{
  "message_type": "heartbeat",
  "event_id": "hb-uuid",
  "timestamp": "2025-10-07T22:00:00+08:00",
  "event_type": "system.module.heartbeat",
  "source_module": "WazuhModule",
  "payload": {
    "module_id": "wazuh_udm_01",
    "status": "online",
    "uptime_seconds": 3600
  }
}
```

### 3. Alert Message (Standard Format)
```json
{
  "message_type": "alert",
  "event_id": "uuid",
  "timestamp": "2025-10-07T22:00:00+08:00",
  "event_type": "alerts.host.wazuh",
  "source_module": "WazuhModule",
  "payload": {
    "severity": "high",
    "alert_type": "ransomware_detection",
    "host_id": "host-192.168.1.105",
    "threat_score": 85,
    ...
  }
}
```

### 4. Workflow Command (from WorkflowEngine)
```json
{
  "message_type": "workflow_command",
  "event_id": "cmd-uuid",
  "timestamp": "2025-10-07T22:00:00+08:00",
  "event_type": "command.execute",
  "source_module": "WorkflowEngine",
  "payload": {
    "workflow_execution_id": "exec-98765",
    "command": "sdn_isolate",
    "target_module": "sdn_controller_01",
    "parameters": {
      "host_id": "host-192.168.1.105"
    }
  }
}
```

---

## Running the System

### Prerequisites
1. **RabbitMQ** running at `192.168.86.76:5672`
2. **PostgreSQL** running at `192.168.86.28:5432`
3. **Database schema** installed (see above)

### 1. Start ModuleRegistry
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager
java -cp "out:lib/*" com.yourorg.registry.ModuleRegistryMain
```

**Expected Output:**
```
╔════════════════════════════════════════════════╗
║  ModuleRegistry & Lifecycle Manager v1.0      ║
╚════════════════════════════════════════════════╝

🔧 Initializing ModuleRegistry...
📂 Loading modules from database...
📂 Loaded 0 modules from database

🚀 Starting components...

📡 AlertBroadcastListener started
   Listening on: workflow_queue
   Broadcasting to: alerts_queue + workflow_queue

🎯 CommandRoutingListener started
   Listening on: workflow_response_queue
   Routing commands to registered UDM queues

💓 HealthMonitor started
   Check interval: 30 seconds
   Heartbeat timeout: 120 seconds

✅ ModuleRegistryAndLifecycleManager is running
================================================
Press CTRL+C to stop
```

### 2. Start UDMTester (Wazuh Simulator)
In a **different terminal**:
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager
java -cp ".:lib/*" UDMTester
```

**Expected Output:**
```
╔═══════════════════════════════════════╗
║  UDM Tester - Wazuh Module Simulator ║
╚═══════════════════════════════════════╝

Module ID: wazuh_udm_01
Module Type: security_monitoring
Command Queue: wazuh_commands_queue

✅ Connected to RabbitMQ at 192.168.86.76
📝 Sent registration to ModuleRegistry
💓 Heartbeat sender started (every 30 seconds)

🎯 Command listener started on: wazuh_commands_queue

═══ UDM Tester Menu ═══
1. Send single ransomware alert
2. Send 10 ransomware alerts
3. Send heartbeat now
4. Exit

Choice:
```

### 3. Send Test Alerts
In the UDMTester terminal, choose option **2** to send 10 ransomware alerts.

**You should see:**
- ModuleRegistry receive the alerts
- Alerts broadcast to both `alerts_queue` and `workflow_queue`
- ThreatContextStore store the alerts
- WorkflowEngine process the alerts and execute workflows

---

## Testing the Complete Flow

### End-to-End Test

1. **Start all systems:**
   ```bash
   # Terminal 1: ThreatContextStore
   cd ThreatContextStore
   java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain
   
   # Terminal 2: WorkflowEngine
   cd WorkflowEngine
   java -cp "out:lib/*" com.yourorg.workflow.WorkflowEngineMain
   
   # Terminal 3: ModuleRegistry
   cd ModuleRegistryLifecycleManager
   java -cp "out:lib/*" com.yourorg.registry.ModuleRegistryMain
   
   # Terminal 4: UDMTester
   cd ModuleRegistryLifecycleManager
   java -cp ".:lib/*" UDMTester
   ```

2. **Send 10 ransomware alerts from UDMTester**

3. **Observe the flow:**
   - UDMTester sends alert to `workflow_queue`
   - ModuleRegistry broadcasts to `alerts_queue` + `workflow_queue`
   - ThreatContextStore stores alert in PostgreSQL
   - WorkflowEngine matches workflows and executes
   - WorkflowEngine sends commands to `workflow_response_queue`
   - ModuleRegistry routes commands to UDM queues
   - UDMTester receives commands (if capability matches)

---

## Configuration

Create `config.properties`:
```properties
# Database
db.host=192.168.86.28
db.port=5432
db.name=wazuhdb
db.user=postgres
db.password=postgres

# RabbitMQ
rabbitmq.host=192.168.86.76
rabbitmq.port=5672
rabbitmq.user=guest
rabbitmq.password=guest

# Queues
rabbitmq.workflow_queue.name=workflow_queue
rabbitmq.alerts_queue.name=alerts_queue
rabbitmq.workflow_response_queue.name=workflow_response_queue

# Health Monitoring
health.heartbeat_timeout_seconds=120
health.check_interval_seconds=30
```

---

## Developing Your Own UDM

See `SDK_Detailed_Context.md` for detailed instructions on creating custom User-Defined Modules.

**Quick Start:**
1. Implement registration, heartbeat, and alert sending
2. Listen to your module's command queue
3. Follow the standard JSON message format
4. Send registration on startup
5. Send heartbeats every 30 seconds

**Example: `UDMTester.java` is a complete reference implementation!**

---

## Troubleshooting

### Module not registering
- Check RabbitMQ connection
- Verify `workflow_queue` exists
- Check ModuleRegistry logs for registration message

### Module marked offline
- Ensure heartbeats are being sent every 30 seconds
- Check network connectivity
- Verify heartbeat message format

### Commands not reaching UDM
- Verify module is registered and online
- Check capability matches command name
- Verify command_queue is correct
- Check CommandRoutingListener logs

### Alerts not broadcasting
- Verify alert has `message_type: "alert"`
- Check AlertBroadcastListener logs
- Verify queues exist in RabbitMQ

---

## System Requirements

- **Java**: 17+
- **RabbitMQ**: 3.x+
- **PostgreSQL**: 17+
- **Operating System**: Linux, macOS, or Windows
- **Memory**: 512MB minimum, 1GB recommended
- **Network**: Access to RabbitMQ and PostgreSQL servers

---

## Files Structure

```
ModuleRegistryLifecycleManager/
├── src/main/java/com/yourorg/registry/
│   ├── ModuleRegistryMain.java           # Main entry point
│   ├── ModuleRegistry.java               # Registry logic
│   ├── AlertBroadcastListener.java       # UDM alert handler
│   ├── CommandRoutingListener.java       # Command router
│   ├── HealthMonitor.java                # Health checker
│   └── ConfigLoader.java                 # Configuration
├── lib/                                  # JAR dependencies
├── out/                                  # Compiled classes
├── UDMTester.java                        # Sample UDM
├── schema_registered_modules.sql         # DB schema
├── SDK_Detailed_Context.md               # SDK documentation
└── README.md                             # This file
```

---

## Next Steps

1. **Test the system** with UDMTester
2. **Develop your own UDMs** using the SDK
3. **Monitor health** via database queries
4. **Scale horizontally** by adding more UDMs
5. **Integrate with real security tools** (Wazuh, Suricata, etc.)

---

**Status: ✅ READY FOR PRODUCTION USE**

The ModuleRegistryAndLifecycleManager is fully implemented, compiled, and tested. Ready to connect your UDMs to the SOAR framework!

