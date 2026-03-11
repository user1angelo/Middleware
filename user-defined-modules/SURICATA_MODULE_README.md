# Suricata Module - User-Defined Module

## Overview

The **SuricataModule** is a user-defined module that integrates Suricata Network Intrusion Detection System (NIDS) with the SOAR framework. It monitors Suricata's `eve.json` log file in real-time and publishes standardized network security alerts.

## Architecture

```
Suricata IDS → eve.json → SuricataModule → workflow_queue (RabbitMQ)
                                                ↓
                                       ModuleRegistry
                                                ↓
                                         alerts_queue
                                                ↓
                                       ThreatContextStore
```

## Features

- **Real-time eve.json monitoring** using Java WatchService
- **Module registration** with ModuleRegistry
- **Periodic heartbeats** to maintain online status
- **Command queue listener** for orchestration commands
- **Standardized alert format** following SDK specifications
- **Configurable** via properties file
- **Threat scoring** and categorization
- **Graceful shutdown** handling

## Files

- `src/main/java/com/nis1/thesis/udm/SuricataModule.java` - Main module implementation
- `src/main/java/com/nis1/thesis/udm/SuricataAlertData.java` - Alert payload data class
- `config/suricata-module.properties` - Configuration file

## Configuration

Edit `config/suricata-module.properties` to configure:

```properties
# RabbitMQ Configuration
rabbitmq.host=localhost
rabbitmq.port=5672
rabbitmq.user=user
rabbitmq.password=password
rabbitmq.workflow_queue=workflow_queue

# Suricata Configuration
suricata.eve_json_path=/var/log/suricata/eve.json

# Module Identity
module.id=suricata_nids_01
module.name=Suricata NIDS Module
module.type=network_security
module.command_queue=suricata_commands_queue

# Capabilities (comma-separated)
module.capabilities=network_ids,alert_generation,packet_analysis,threat_detection

# Heartbeat Interval (seconds)
module.heartbeat_interval=30
```

## Prerequisites

1. **Java 17+** installed
2. **Suricata IDS** running with eve.json logging enabled
3. **RabbitMQ** server accessible
4. **ModuleRegistry** and **LifecycleManager** running
5. Required JAR dependencies (in `../ModuleRegistryLifecycleManager/lib/`):
   - `amqp-client-5.26.0.jar` (RabbitMQ client)
   - `json-20231013.jar` (JSON processing)
   - `gson-2.13.1.jar` (JSON serialization)
   - `slf4j-api-2.0.17.jar` and `slf4j-simple-2.0.17.jar` (logging)

## Build Instructions

### Compile

From the `user-defined-modules` directory:

```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/user-defined-modules

# Create output directory
mkdir -p target/classes

# Compile
javac -cp "../ModuleRegistryLifecycleManager/lib/*" \
  -d target/classes \
  src/main/java/com/nis1/thesis/udm/SuricataModule.java \
  src/main/java/com/nis1/thesis/udm/SuricataAlertData.java
```

### Run

```bash
# Ensure config file exists
ls config/suricata-module.properties

# Run the module
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*" \
  com.nis1.thesis.udm.SuricataModule
```

## Message Flow

### 1. Registration Message

On startup, the module sends a registration message to `workflow_queue`:

```json
{
  "message_type": "registration",
  "event_id": "reg-<uuid>",
  "timestamp": "2025-11-18T10:48:00+08:00",
  "event_type": "system.module.registration",
  "source_module": "Suricata NIDS Module",
  "payload": {
    "module_id": "suricata_nids_01",
    "module_name": "Suricata NIDS Module",
    "module_type": "network_security",
    "capabilities": ["network_ids", "alert_generation", "packet_analysis", "threat_detection"],
    "command_queue": "suricata_commands_queue",
    "version": "2.0.0",
    "metadata": {
      "vendor": "Suricata",
      "log_source": "/var/log/suricata/eve.json",
      "detection_type": "network_ids"
    }
  }
}
```

### 2. Heartbeat Message

Every 30 seconds (configurable):

```json
{
  "message_type": "heartbeat",
  "event_id": "hb-<uuid>",
  "timestamp": "2025-11-18T10:48:30+08:00",
  "event_type": "system.module.heartbeat",
  "source_module": "Suricata NIDS Module",
  "payload": {
    "module_id": "suricata_nids_01",
    "status": "online",
    "uptime_seconds": 3600
  }
}
```

### 3. Alert Message

When Suricata detects a threat:

```json
{
  "message_type": "alert",
  "event_id": "<uuid>",
  "timestamp": "2025-11-18T10:49:15Z",
  "event_type": "alerts.network.suricata",
  "source_module": "Suricata NIDS Module",
  "payload": {
    "alertId": "SURI-1731898155000-1234",
    "signatureId": "2024123",
    "signature": "ET MALWARE Suspicious Outbound Connection",
    "sourceIp": "192.168.1.100",
    "destinationIp": "203.0.113.50",
    "sourcePort": 54321,
    "destinationPort": 443,
    "protocol": "TCP",
    "severity": "high",
    "category": "malware",
    "alertType": "malware",
    "threatScore": 70,
    "confidenceScore": 95,
    "action": "allowed",
    "flowId": "123456789"
  }
}
```

## Event Routing

- **Registration/Heartbeat** → `workflow_queue` → ModuleRegistry → Database
- **Alerts** → `workflow_queue` → ModuleRegistry → `alerts_queue` → ThreatContextStore
- **Commands** → `suricata_commands_queue` → SuricataModule (handles commands)

## Differences from Original SuricataModule

| Aspect | Original | Refactored (SDK-aligned) |
|--------|----------|--------------------------|
| **Location** | `/Middleware/SuricataModule.java` | `user-defined-modules/src/main/java/com/nis1/thesis/udm/` |
| **Configuration** | Hardcoded constants | `config/suricata-module.properties` |
| **Message Queue** | Direct to `alerts_queue` | To `workflow_queue` (via ModuleRegistry) |
| **Payload Class** | Inline JSONObject | Dedicated `SuricataAlertData` class |
| **Module Pattern** | Standalone | Follows PRTGModule/SDK UDM pattern |
| **Registration** | Direct to alerts_queue | Via ModuleRegistry on workflow_queue |
| **Heartbeat** | Commented out | Active, sends to workflow_queue |

## Troubleshooting

### Module won't start

- **Check eve.json exists**: `ls -la /var/log/suricata/eve.json`
- **Update config**: Edit `suricata.eve_json_path` in config file
- **Check RabbitMQ**: Ensure RabbitMQ is running and accessible

### No alerts appearing

- **Check Suricata is generating alerts**: `tail -f /var/log/suricata/eve.json`
- **Check file permissions**: Module needs read access to eve.json
- **Check RabbitMQ queues**: `rabbitmqctl list_queues`

### Module not registered in ModuleRegistry

- **Check ModuleRegistry is running**: Should see registration acknowledgment
- **Check workflow_queue**: `rabbitmqctl list_queues | grep workflow`
- **Check database**: Query `registered_modules` table

## Testing

### Test with sample eve.json alert

```bash
# Append a test alert to eve.json (requires appropriate permissions)
echo '{"timestamp":"2025-11-18T10:50:00.000000+0800","event_type":"alert","src_ip":"192.168.1.100","dest_ip":"10.0.0.1","src_port":12345,"dest_port":80,"proto":"TCP","alert":{"signature":"Test Alert","signature_id":9999999,"severity":2,"category":"Test"}}' >> /var/log/suricata/eve.json
```

The module should immediately detect and process this alert.

## Advanced Usage

### Custom Command Handling

The module listens on `suricata_commands_queue`. You can extend the `handleCommand()` method to support additional commands:

```java
case "command.suricata.reload_rules":
    // Trigger Suricata rule reload
    break;
case "command.suricata.status":
    // Return module status
    break;
```

### Custom Categorization

Modify the `categorizeFromSignature()` method to add custom threat categorization logic based on your environment.

## Integration with Workflow Engine

The WorkflowEngine can send commands to this module via the `suricata_commands_queue`. Example workflow actions:

- Reload Suricata rules after threat intelligence update
- Request module status for health monitoring
- Adjust detection thresholds dynamically

## Next Steps

1. Ensure `config/suricata-module.properties` is configured for your environment
2. Start ModuleRegistry: `java -cp ... com.yourorg.registry.ModuleRegistryMain`
3. Start SuricataModule: `java -cp ... com.nis1.thesis.udm.SuricataModule`
4. Verify registration in ModuleRegistry logs
5. Monitor alert flow to ThreatContextStore

## References

- SDK Documentation: `ModuleRegistryLifecycleManager/SDK_Detailed_Context.md`
- Module Registry: `ModuleRegistryLifecycleManager/ModuleLifecycleRegistryManager.md`
- Original Module: `/Middleware/SuricataModule.java`
