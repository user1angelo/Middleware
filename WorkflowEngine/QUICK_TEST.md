# Quick Test - 4 Terminal Setup

## Terminal Layout
```
┌─────────────────────┬─────────────────────┐
│  Terminal 1         │  Terminal 2         │
│  ModuleRegistry     │  WorkflowEngine     │
├─────────────────────┼─────────────────────┤
│  Terminal 3         │  Terminal 4         │
│  Monitor Responses  │  Send Alerts        │
└─────────────────────┴─────────────────────┘
```

## Terminal 1: ModuleRegistry
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager
java -cp "target/classes:lib/*" com.yourorg.registry.ModuleRegistryMain
```

Wait for:
```
✅ ModuleRegistryAndLifecycleManager is running
```

## Terminal 2: WorkflowEngine
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/WorkflowEngine
java -cp "target/classes:lib/*" com.yourorg.workflow.WorkflowEngineMain
```

Wait for:
```
✅ Listener thread started successfully
```

## Terminal 3: Monitor Responses
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/WorkflowEngine
java -cp ".:lib/*" WorkflowTester
```

Choose: **3** (Monitor workflow_response_queue)

Wait for:
```
✅ Connected to workflow_response_queue
   Waiting for workflow responses...
```

## Terminal 4: Send Alerts
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/WorkflowEngine
java -cp ".:lib/*" WorkflowTester
```

Choose: **1** (Send single ransomware alert)

## Expected Results

### Terminal 1 (ModuleRegistry):
```
📨 Received message type: alert from WorkflowTester
📤 Broadcasted alert | Severity: high | Type: ransomware_detection | To: both queues
```

### Terminal 2 (WorkflowEngine):
```
📨 Alert #1 received
🏷️  Event ID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
📊 Alert Type: ransomware_detection
🎯 Severity: high

🔍 Looking for general ransomware workflow...
✅ Found general workflow: General Ransomware Response
🚀 Executing workflow: General Ransomware Response
📤 Published to workflow_response_queue

✅ ACK sent for alert #1
```

### Terminal 3 (Monitor):
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📨 WORKFLOW RESPONSE #1
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Message Type: workflow_command
Event ID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
Source: WorkflowEngine

📦 Payload:
   Workflow Execution ID: exec-xxxxx
   Workflow Name: General Ransomware Response
   Command: INITIATE_MITIGATION
   ...
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### Terminal 4 (Tester):
```
✅ Sent alert #1
   Event ID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   Alert Type: ransomware_detection
   Severity: high
```

## Test Options

### Option 1: Single Alert
Generic ransomware alert - triggers general workflow

### Option 2: 10 Alerts
Load test - sends 10 alerts in sequence

### Option 3: Monitor Queue
**Start this FIRST** before sending alerts!

### Option 4: Dharma Alert
Specific alert that triggers Dharma file encryption workflow

## Troubleshooting

### No response in Terminal 3?
1. Check Terminal 2 - is WorkflowEngine processing the alert?
2. Check if alert is ransomware type (check Terminal 4 output)
3. Verify workflow YAML files exist: `ls workflows/ransomware/`

### Connection error?
1. Check RabbitMQ: `telnet 192.168.1.8 5672`
2. Verify credentials: user/password
3. Check config.properties

### ModuleRegistry not receiving alerts?
1. Check it's listening to `workflow_queue`
2. Check startup logs
3. Verify RabbitMQ connection

## Clean Shutdown

1. **Terminal 3**: Press ENTER to stop monitoring
2. **Terminal 4**: Choose 5 to exit
3. **Terminal 2**: CTRL+C to stop WorkflowEngine
4. **Terminal 1**: CTRL+C to stop ModuleRegistry

## Quick Retest

After first successful test:
- Keep Terminal 1 & 2 running
- In Terminal 4: Send more alerts (Option 1, 2, or 4)
- Watch Terminal 3 for responses

## Success Checklist

- [ ] ModuleRegistry started and listening
- [ ] WorkflowEngine started and listening
- [ ] Monitor connected to workflow_response_queue
- [ ] Alert sent successfully
- [ ] ModuleRegistry receives and broadcasts alert
- [ ] WorkflowEngine processes alert
- [ ] Workflow executes
- [ ] Response appears in monitor

✅ All checked? Your pipeline is working perfectly!

