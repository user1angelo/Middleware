# Zeek Module - User-Defined Module

## Overview

The **ZeekModule** is a user-defined module that integrates Zeek Network Security Monitor (NSM) with the SOAR framework. It monitors Zeek's `notice.log` file in real-time and publishes standardized network security alerts for ransomware-related activity.

## Architecture

```
Zeek NSM → notice.log → ZeekModule → workflow_queue (RabbitMQ)
                                             ↓
                                    ModuleRegistry
                                             ↓
                                      alerts_queue
                                             ↓
                                    ThreatContextStore
                                             ↓
                                    WorkflowEngine
                                             ↓
                                    OpenDaylightModule (SDK)
                                             ↓
                                    Host Isolation
```

## Features

- **Real-time notice.log monitoring** using Java WatchService
- **Module registration** with ModuleRegistry
- **Periodic heartbeats** to maintain online status
- **Command queue listener** for orchestration commands
- **Standardized alert format** following SDK specifications
- **Ransomware-focused detection** with configurable patterns
- **Graceful shutdown** handling

## Files

- `src/main/java/com/nis1/thesis/udm/ZeekModule.java` - Main module implementation
- `config/zeek-module.properties` - Configuration file

## Configuration

Edit `config/zeek-module.properties` to configure:

```properties
# RabbitMQ Configuration
rabbitmq.host=localhost
rabbitmq.port=5672
rabbitmq.user=user
rabbitmq.password=password
rabbitmq.workflow_queue=workflow_queue

# Zeek Configuration
zeek.notice_log_path=/opt/zeek/logs/current/notice.log

# Module Identity
module.id=zeek-module
module.name=Zeek NSM Module
module.type=network_security
module.command_queue=zeek-module_commands_queue

# Capabilities (comma-separated)
module.capabilities=network_monitoring,protocol_analysis,alert_generation,threat_detection

# Heartbeat Interval (seconds)
module.heartbeat_interval=30
```

## Prerequisites

1. **Java 17+** installed
2. **Zeek** running with notice.log enabled
3. **RabbitMQ** server accessible
4. **ModuleRegistry** running
5. Required JAR dependencies (in `../ModuleRegistryLifecycleManager/lib/`):
   - `amqp-client-5.26.0.jar` (RabbitMQ client)
   - `json-20231013.jar` (JSON processing)
   - `slf4j-api-2.0.17.jar` and `slf4j-simple-2.0.17.jar` (logging)

## Build & Run

### Using Maven (Recommended)

```bash
cd user-defined-modules
mvn clean compile

# Run
mvn exec:java -Dexec.mainClass="com.nis1.thesis.udm.ZeekModule"
```

### Manual Compilation

```bash
cd /path/to/Middleware/user-defined-modules

# Create output directory
mkdir -p out

# Compile
javac -cp "../ModuleRegistryLifecycleManager/lib/*" \
  -d out \
  src/main/java/com/nis1/thesis/udm/ZeekModule.java

# Run
java -cp "out:../ModuleRegistryLifecycleManager/lib/*:config" \
  com.nis1.thesis.udm.ZeekModule
```

## Alert Message Format

When Zeek detects ransomware-related activity:

```json
{
  "message_type": "alert",
  "event_id": "<uuid>",
  "timestamp": "2026-02-09T23:30:00Z",
  "event_type": "alerts.network.zeek",
  "source_module": "Zeek NSM Module",
  "payload": {
    "alert_id": "ZEEK-1707494400000-1234",
    "note_type": "Scan::Port_Scan",
    "signature": "192.168.1.100 scanned at least 25 unique hosts on port 445/tcp in 0m5s",
    "severity": "high",
    "source_ip": "192.168.1.100",
    "destination_ip": "192.168.1.0/24",
    "protocol": "TCP",
    "category": "ransomware",
    "alert_type": "ransomware",
    "threat_score": 75,
    "confidence_score": 90
  }
}
```

## Ransomware Detection Patterns

The module detects the following patterns in Zeek notices:

- **Ransomware keywords**: ransomware, wannacry, petya, dharma, ryuk
- **Malware indicators**: malware, trojan, crypto
- **C2 communication**: c2, command and control
- **Lateral movement**: lateral, smb, eternalblue
- **Reconnaissance**: Scan::Port_Scan, Scan::Address_Scan
- **Threat intel matches**: Intel::Notice

## Testing

### Generate a test notice

Create a test entry in Zeek's notice.log:

```bash
# Simulate a ransomware-related notice
echo -e "1707494400.000000\tCtest123\t192.168.1.100\t12345\t10.0.0.1\t445\t-\ttcp\tScan::Port_Scan\t192.168.1.100 scanned at least 25 hosts on port 445/tcp - Possible WannaCry ransomware\t-\t192.168.1.100\t-\t-\t-\t-\tNotice::ACTION_LOG" >> /opt/zeek/logs/current/notice.log
```

The module should detect and publish an alert.

## Workflow Integration

The `zeek_ransomware_response.yml` workflow triggers on Zeek alerts:

1. Logs the ransomware detection event
2. Initiates host network isolation via OpenDaylight
3. Alerts the Security Operations Center

## Troubleshooting

### Module won't start

- **Check notice.log exists**: `ls -la /opt/zeek/logs/current/notice.log`
- **Update config**: Edit `zeek.notice_log_path` in config file
- **Check RabbitMQ**: Ensure RabbitMQ is running and accessible

### No alerts appearing

- **Check Zeek is generating notices**: `tail -f /opt/zeek/logs/current/notice.log`
- **Check file permissions**: Module needs read access to notice.log
- **Check RabbitMQ queues**: `rabbitmqctl list_queues`

### Alerts not triggering workflows

- **Check alert_type**: Must contain "ransomware" for workflow matching
- **Check WorkflowEngine logs**: Look for matching/execution messages
