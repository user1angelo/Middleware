# ODL Network Enforcer Module

An OpenDaylight (ODL) Carbon module that integrates with the middleware workflow engine to perform network enforcement operations via OpenFlow and OVS.

## Overview

This module consumes commands from `workflow_command_queue` (RabbitMQ) and executes network operations through OpenDaylight's MD-SAL APIs:
- **Flow rule management** - Add/modify OpenFlow rules
- **Host isolation** - Block traffic to/from malicious hosts
- **Topology discovery** - Query network topology

## Architecture

```
Workflow Engine → workflow_command_queue → ODL Network Enforcer → OpenDaylight MD-SAL → OVS (Mininet)
```

### Components

- **API Bundle** - Interfaces and models (`WorkflowCommand`, `CommandHandler`)
- **Implementation Bundle** - RabbitMQ listener, command handlers, ODL service integration
- **Features** - Karaf feature descriptor for OSGi deployment

### Command Handlers

1. **FlowAddHandler** - Installs OpenFlow rules
2. **HostIsolationHandler** - Blocks traffic from malicious hosts
3. **TopologyDiscoveryHandler** - Discovers network topology

## Prerequisites

- **Java 8+**
- **Maven 3.x**
- **OpenDaylight Carbon 0.6.4** (running)
- **RabbitMQ** (accessible at configured host/port)
- **Mininet with OVS** (connected to ODL)

## Configuration

### Create config.properties

```bash
cd odl-network-enforcer
cp config.properties.example config.properties
```

### Configuration Options

Edit `config.properties`:

```properties
# RabbitMQ Configuration
rabbitmq.host=192.168.86.76
rabbitmq.port=5672
rabbitmq.user=guest
rabbitmq.password=guest
rabbitmq.workflow_command_queue.name=workflow_command_queue

# OpenDaylight Configuration
odl.host=localhost
odl.port=8181
odl.user=admin
odl.password=admin

# Flow Configuration
flow.default.priority=100
flow.isolation.priority=1000
flow.idle.timeout=0
flow.hard.timeout=0
```

## Build

### Compile the Module

```bash
cd odl-network-enforcer
mvn clean install
```

This produces:
- `api/target/odl-network-enforcer-api-1.0.0-SNAPSHOT.jar`
- `impl/target/odl-network-enforcer-impl-1.0.0-SNAPSHOT.jar`
- `features/target/odl-network-enforcer-features-1.0.0-SNAPSHOT.jar`

### Deploy to OpenDaylight

#### Option 1: Karaf Feature (Recommended)

1. Copy feature repository to ODL:
   ```bash
   cp features/target/odl-network-enforcer-features-1.0.0-SNAPSHOT.jar \
      $ODL_HOME/deploy/
   ```

2. In ODL Karaf console:
   ```bash
   feature:repo-add mvn:com.yourorg.odl/odl-network-enforcer-features/1.0.0-SNAPSHOT/xml/features
   feature:install odl-network-enforcer
   ```

#### Option 2: Direct Bundle Deployment

Copy bundles to ODL deploy directory:
```bash
cp api/target/odl-network-enforcer-api-1.0.0-SNAPSHOT.jar $ODL_HOME/deploy/
cp impl/target/odl-network-enforcer-impl-1.0.0-SNAPSHOT.jar $ODL_HOME/deploy/
```

## Command Message Format

All commands follow the middleware message structure:

```json
{
  "message_type": "odl.<command>",
  "event_id": "uuid",
  "timestamp": "ISO-8601 timestamp",
  "event_type": "workflow.command",
  "source_module": "WorkflowEngine",
  "payload": { /* command-specific data */ }
}
```

### Supported Commands

#### 1. Host Isolation (odl.host.isolate)

Blocks all traffic to/from a specified host.

**Message:**
```json
{
  "message_type": "odl.host.isolate",
  "event_id": "cmd-12345678-1234-1234-1234-123456789abc",
  "timestamp": "2025-01-18T03:00:00Z",
  "event_type": "workflow.command",
  "source_module": "WorkflowEngine",
  "payload": {
    "ip_address": "10.0.0.5",
    "mac_address": "00:00:00:00:00:05",
    "node_id": "openflow:1",
    "reason": "Detected malicious activity"
  }
}
```

**Fields:**
- `ip_address` - IP address to block (optional if MAC provided)
- `mac_address` - MAC address to block (optional if IP provided)
- `node_id` - OpenFlow node ID (default: `openflow:1`)
- `reason` - Reason for isolation (for logging)

#### 2. Flow Add (odl.flow.add)

Installs an OpenFlow rule.

**Message:**
```json
{
  "message_type": "odl.flow.add",
  "event_id": "cmd-87654321-4321-4321-4321-cba987654321",
  "timestamp": "2025-01-18T03:00:00Z",
  "event_type": "workflow.command",
  "source_module": "WorkflowEngine",
  "payload": {
    "node_id": "openflow:1",
    "table_id": 0,
    "priority": 200,
    "match": {
      "eth_type": "0x0800",
      "ipv4_src": "10.0.0.1",
      "ipv4_dst": "10.0.0.2"
    },
    "actions": ["output:2"],
    "idle_timeout": 0,
    "hard_timeout": 0
  }
}
```

**Fields:**
- `node_id` - OpenFlow node ID (required)
- `table_id` - Flow table ID (default: 0)
- `priority` - Flow priority (default: from config)
- `match` - Match criteria (JSON object)
- `actions` - Actions to apply (JSON array)
- `idle_timeout` - Idle timeout in seconds (default: 0)
- `hard_timeout` - Hard timeout in seconds (default: 0)

#### 3. Topology Discovery (odl.topology.discover)

Queries the network topology.

**Message:**
```json
{
  "message_type": "odl.topology.discover",
  "event_id": "cmd-11111111-2222-3333-4444-555555555555",
  "timestamp": "2025-01-18T03:00:00Z",
  "event_type": "workflow.command",
  "source_module": "WorkflowEngine",
  "payload": {
    "topology_id": "flow:1"
  }
}
```

**Fields:**
- `topology_id` - Topology ID to query (default: `flow:1`)

## Testing

### Prerequisites

1. **Start RabbitMQ:**
   ```bash
   sudo systemctl start rabbitmq-server
   ```

2. **Start OpenDaylight:**
   ```bash
   cd $ODL_HOME
   ./bin/karaf
   ```

3. **Start Mininet with OVS:**
   ```bash
   sudo mn --controller=remote,ip=127.0.0.1 --topo=tree,2
   ```

### Test Messages

Sample test messages are in `test-messages/` directory:
- `host_isolate.json` - Host isolation command
- `flow_add.json` - Flow addition command
- `topology_discover.json` - Topology discovery command

### Send Test Commands

#### Using Python (RabbitMQ)

```python
import pika
import json

connection = pika.BlockingConnection(
    pika.ConnectionParameters('192.168.86.76'))
channel = connection.channel()
channel.queue_declare(queue='workflow_command_queue', durable=True)

with open('test-messages/host_isolate.json') as f:
    message = json.load(f)

channel.basic_publish(
    exchange='',
    routing_key='workflow_command_queue',
    body=json.dumps(message))

connection.close()
```

#### Using rabbitmqadmin CLI

```bash
# Publish host isolation command
rabbitmqadmin publish routing_key=workflow_command_queue \
  payload="$(cat test-messages/host_isolate.json)"
```

### Verify in ODL Logs

```bash
tail -f $ODL_HOME/data/log/karaf.log | grep "ODL Network Enforcer"
```

Expected output:
```
INFO  [WorkflowCommandListener] Received workflow command: {...}
INFO  [HostIsolationHandler] Isolating host - IP: 10.0.0.5, MAC: 00:00:00:00:00:05
INFO  [HostIsolationHandler] Successfully isolated host 10.0.0.5
```

## Module Detection

The compiled JAR file will be located at:
```
user-defined-modules/odl-network-enforcer/impl/target/odl-network-enforcer-impl-1.0.0-SNAPSHOT.jar
```

This JAR can be detected by the module registry and lifecycle manager for deployment.

## Troubleshooting

### Module not starting

**Check OSGi bundle status:**
```bash
# In Karaf console
bundle:list | grep enforcer
```

**View bundle details:**
```bash
bundle:diag <bundle-id>
```

### RabbitMQ connection issues

**Check config.properties:**
- Verify RabbitMQ host/port are correct
- Test connection: `telnet 192.168.86.76 5672`

### No commands being processed

**Verify queue exists:**
```bash
sudo rabbitmqctl list_queues
```

**Check logs:**
```bash
grep "WorkflowCommandListener" $ODL_HOME/data/log/karaf.log
```

## Integration with Production ODL

The current implementation includes **simulated** flow operations (logging only). To integrate with actual OpenDaylight:

1. **Inject MD-SAL DataBroker** in handlers
2. **Implement flow programming** using `SalFlowService`
3. **Use ODL topology service** for real topology queries

See TODOs in handler classes for specific integration points.

## License

This module is part of the middleware project and follows the same licensing terms.
