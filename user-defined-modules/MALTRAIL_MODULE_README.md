# Maltrail Module - User-Defined Module

## Overview

The **MaltrailModule** is a user-defined module that integrates [Maltrail](https://github.com/stamparm/maltrail) threat-intelligence trail matching with the SOAR framework. It listens for Maltrail's Logstash-style UDP JSON events in real-time and publishes standardized network security alerts, as a second, independent NIDS-style data source alongside Suricata.

Unlike SuricataModule (which tails a local `eve.json` log file), MaltrailModule listens on a UDP socket, matching Maltrail's push-based `LOGSTASH_SERVER` delivery mechanism. Maltrail's local log file format is a different, unstructured space-delimited text format and is intentionally not parsed here.

## Architecture

```
Maltrail sensor → UDP JSON (LOGSTASH_SERVER) → MaltrailModule → workflow_queue (RabbitMQ)
                                                                        ↓
                                                                 ModuleRegistry
                                                                        ↓
                                                                  alerts_queue
                                                                        ↓
                                                                ThreatContextStore
```

## Features

- **Real-time UDP listening** for Maltrail's Logstash-style JSON events
- **Module registration** with ModuleRegistry
- **Periodic heartbeats** to maintain online status
- **Command queue listener** for orchestration commands
- **Standardized alert format** following SDK specifications, field-compatible with SuricataAlertData
- **Configurable** via properties file
- **Severity normalization and threat scoring**
- **Graceful shutdown** handling

## Zero core changes

Adding this module required **no changes** to `CoreSystemApi`, `WorkflowMatcher`, `WorkflowEngine`, or `OpenDaylightModule`. It publishes to the same `workflow_queue` with the same `basicPublish("", queueName, ...)` shape SuricataModule already uses, and `MaltrailAlertData` mirrors `SuricataAlertData`'s field names so `WorkflowMatcher`'s existing condition evaluation (severity, alert_type/category, signature, threat_score) works unmodified. See `MALTRAIL_DEVELOPER_USABILITY_LOG.md` for the tracked file-change footprint.

## Files

- `src/main/java/com/nis1/thesis/udm/MaltrailModule.java` - Main module implementation
- `src/main/java/com/nis1/thesis/udm/MaltrailAlertData.java` - Alert payload data class
- `config/maltrail-module.properties` - Configuration file

## Configuration

Edit `config/maltrail-module.properties` to configure:

```properties
# RabbitMQ Configuration
rabbitmq.host=localhost
rabbitmq.port=5672
rabbitmq.user=user
rabbitmq.password=password
rabbitmq.workflow_queue=workflow_queue

# Maltrail UDP listener
maltrail.udp_port=8481

# Module Identity
module.id=maltrail-module
module.name=Maltrail UDM
module.type=network_security
module.command_queue=maltrail-module_commands_queue

# Capabilities (comma-separated)
module.capabilities=network_ids,threat_intel,alert_generation

# Heartbeat Interval (seconds)
module.heartbeat_interval=30
```

On the Maltrail sensor side, add to `maltrail.conf`:

```
LOGSTASH_SERVER <this-host>:8481
```

## Prerequisites

1. **Java 17+** installed
2. **Maltrail** sensor configured with `LOGSTASH_SERVER` pointing at this module's host:port (or use `scripts/send_test_maltrail_event.py` from the repo root to simulate one)
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
cd user-defined-modules

# Create output directory
mkdir -p target/classes

# Compile
javac -cp "../ModuleRegistryLifecycleManager/lib/*" \
  -d target/classes \
  src/main/java/com/nis1/thesis/udm/MaltrailModule.java \
  src/main/java/com/nis1/thesis/udm/MaltrailAlertData.java
```

Or via Maven (this module is part of the reactor build): `mvn -pl user-defined-modules compile`.

### Run

```bash
# Ensure config file exists
ls config/maltrail-module.properties

# Run the module (must be launched with CWD = user-defined-modules/,
# since config is loaded from a hardcoded relative path, same as SuricataModule)
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*" \
  com.nis1.thesis.udm.MaltrailModule
```

## Message Flow

### 1. Registration Message

On startup, the module sends a registration message to `workflow_queue`:

```json
{
  "message_type": "registration",
  "event_id": "reg-<uuid>",
  "timestamp": "2026-07-25T10:48:00+08:00",
  "event_type": "system.module.registration",
  "source_module": "Maltrail UDM",
  "payload": {
    "module_id": "maltrail-module",
    "module_name": "Maltrail UDM",
    "module_type": "network_security",
    "capabilities": ["network_ids", "threat_intel", "alert_generation"],
    "command_queue": "maltrail-module_commands_queue",
    "version": "1.0.0",
    "metadata": {
      "vendor": "Maltrail",
      "log_source": "udp://0.0.0.0:8481",
      "detection_type": "threat_intel"
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
  "timestamp": "2026-07-25T10:48:30+08:00",
  "event_type": "system.module.heartbeat",
  "source_module": "Maltrail UDM",
  "payload": {
    "module_id": "maltrail-module",
    "status": "online",
    "uptime_seconds": 3600
  }
}
```

### 3. Alert Message

When a Maltrail sensor matches a known indicator (trail):

```json
{
  "message_type": "alert",
  "event_id": "<uuid>",
  "timestamp": "2026-07-25T10:49:15Z",
  "event_type": "alerts.network.maltrail",
  "source_module": "Maltrail UDM",
  "payload": {
    "alert_id": "MALT-1a2b3c4d",
    "signature": "ransomware",
    "source_ip": "10.0.0.55",
    "destination_ip": "203.0.113.9",
    "source_port": 51234,
    "destination_port": 443,
    "protocol": "tcp",
    "severity": "high",
    "category": "ransomware",
    "alert_type": "ransomware",
    "threat_score": 75,
    "confidence_score": 90,
    "trail": "203.0.113.9",
    "reference": "abuse.ch",
    "sensor": "sensor-01"
  }
}
```

Note the field names are the real Gson `@SerializedName` wire format (snake_case) - matching `MaltrailAlertData.java`'s source exactly, unlike the earlier Suricata README example which drifted from the actual source.

## Field Mapping (Maltrail -> alert payload)

| Maltrail field | payload field | Notes |
|---|---|---|
| `info` | `signature`, and feeds `category`/`alert_type` | Keyword-matched (e.g. "ransomware", "malware", "c2") |
| `severity` | `severity` | Normalized to critical/high/medium/low; blank/unrecognized defaults to `high` (a Maltrail hit is always a confirmed indicator match, unlike Suricata's broader pattern matching) |
| `type` (ip/dns/url) | fallback for `category`/`alert_type` | Used as `maltrail_<type>` only when `info` doesn't match a known keyword |
| `src_ip`/`dst_ip`/`src_port`/`dst_port`/`proto` | direct passthrough | |
| `trail`, `reference`, `sensor` | passthrough (Maltrail-specific, no Suricata equivalent) | |
| n/a | `alert_id` | Generated: `MALT-` + hex hash of timestamp+src+dst+trail |
| n/a | `threat_score` | Derived from severity + category, same scale as SuricataModule |
| n/a | `confidence_score` | Fixed at 90 |

## Event Routing

- **Registration/Heartbeat** → `workflow_queue` → ModuleRegistry → Database
- **Alerts** → `workflow_queue` → ModuleRegistry → `alerts_queue` → ThreatContextStore
- **Commands** → `maltrail-module_commands_queue` → MaltrailModule (handles commands)

## Troubleshooting

### Module won't start

- **Check the UDP port isn't already in use**: another process listening on `maltrail.udp_port` will cause the bind to fail
- **Check RabbitMQ**: Ensure RabbitMQ is running and accessible

### No alerts appearing

- **Check Maltrail's `LOGSTASH_SERVER` config** points at this module's host:port
- **Check firewall rules** allow inbound UDP on the configured port
- **Send a test event**: `python scripts/send_test_maltrail_event.py` (from repo root)
- **Check RabbitMQ queues**: `rabbitmqctl list_queues`

### Module not registered in ModuleRegistry

- **Check ModuleRegistry is running**: Should see registration acknowledgment
- **Check workflow_queue**: `rabbitmqctl list_queues | grep workflow`
- **Check database**: Query `registered_modules` table

## Testing

### Test with a simulated Maltrail UDP event

From the repo root:

```bash
python scripts/send_test_maltrail_event.py --host localhost --port 8481
```

This sends a single UDP JSON packet matching Maltrail's documented schema (a high-severity ransomware-trail hit) so you can confirm the full pipeline: `MaltrailModule` receives it → publishes to `workflow_queue` → `WorkflowMatcher` matches `maltrail_ransomware_isolate.yml` → an `INITIATE_MITIGATION` command is dispatched.

## Advanced Usage

### Custom Command Handling

The module listens on `maltrail-module_commands_queue`. You can extend the `handleCommand()` method to support additional commands, e.g.:

```java
case "command.maltrail.status":
    // Return module status
    break;
```

### Custom Categorization

Modify the `categorizeFromInfo()` method to add custom threat categorization logic based on your Maltrail trail lists.

## Integration with Workflow Engine

See `WorkflowEngine/workflows/ransomware/maltrail_ransomware_isolate.yml` for the workflow that gates on `event_type == "alerts.network.maltrail"` with `severity == 'high' and alert_type contains 'ransomware'`, dispatching an `INITIATE_MITIGATION` / `ISOLATE_VLAN` command - the same downstream path Suricata-sourced alerts already use, unmodified.

## Next Steps

1. Ensure `config/maltrail-module.properties` is configured for your environment
2. Start ModuleRegistry: `java -cp ... com.yourorg.registry.ModuleRegistryMain`
3. Start MaltrailModule: `java -cp ... com.nis1.thesis.udm.MaltrailModule`
4. Verify registration in ModuleRegistry logs
5. Point a real Maltrail sensor's `LOGSTASH_SERVER` at this module, or use `scripts/send_test_maltrail_event.py` to simulate one
6. Monitor alert flow to ThreatContextStore

## References

- SDK Documentation: `ModuleRegistryLifecycleManager/SDK_Detailed_Context.md`
- Module Registry: `ModuleRegistryLifecycleManager/ModuleLifecycleRegistryManager.md`
- Sibling module (same pattern, file-tail instead of UDP): `SURICATA_MODULE_README.md`
- Maltrail project: https://github.com/stamparm/maltrail
