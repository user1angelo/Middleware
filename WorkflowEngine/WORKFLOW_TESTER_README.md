# WorkflowTester - End-to-End Testing Guide

## Overview

`WorkflowTester.java` is a complete end-to-end test script for the WorkflowEngine system. It sends alerts and monitors workflow responses to verify the entire pipeline.

## Flow Diagram

```
WorkflowTester
    ↓ (sends alert)
workflow_queue
    ↓
ModuleRegistry (AlertBroadcastListener)
    ↓ (broadcasts)
    ├─→ alerts_queue (ThreatContextStore)
    └─→ workflow_queue (WorkflowEngine)
         ↓
WorkflowEngine (processes alert, executes workflows)
    ↓
workflow_response_queue
    ↓
WorkflowTester (monitors and displays)
```

## Prerequisites

Before running the tester:

1. **RabbitMQ** running at `192.168.1.8:5672`
2. **PostgreSQL** running at `192.168.1.8:5432`
3. **ModuleRegistry** running
4. **WorkflowEngine** running
5. **JAR dependencies** in `lib/` directory

## Compilation

```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/WorkflowEngine
javac -cp ".:lib/*" WorkflowTester.java
```

## Running the Tester

```bash
java -cp ".:lib/*" WorkflowTester
```

## Menu Options

### 1. Send single ransomware alert
- Sends one generic ransomware alert
- Alert type: `ransomware_detection`
- Severity: `high`
- Threat score: 85-99
- Should trigger: General ransomware response workflow

### 2. Send 10 ransomware alerts
- Sends 10 generic ransomware alerts in sequence
- Useful for load testing
- Each alert has unique event ID and randomized IPs

### 3. Monitor workflow_response_queue
- **IMPORTANT**: Start this BEFORE sending alerts!
- Continuously listens to `workflow_response_queue`
- Displays all workflow command messages
- Press ENTER to stop monitoring

### 4. Send Dharma file encryption alert
- Sends a specific alert designed to trigger Dharma workflow
- Event type: `HOST_ALERT_WAZUH`
- Rule description: "Dharma Ransomware File Encryption Detected"
- Should trigger: `dharma-host-file-encryption.yml` workflow

### 5. Exit
- Exits the tester

## Testing Procedure

### Complete End-to-End Test

**Terminal 1: Start ModuleRegistry**
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager
java -cp "target/classes:lib/*" com.yourorg.registry.ModuleRegistryMain
```

**Terminal 2: Start WorkflowEngine**
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/WorkflowEngine
java -cp "target/classes:lib/*" com.yourorg.workflow.WorkflowEngineMain
```

**Terminal 3: Run WorkflowTester**
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/WorkflowEngine
java -cp ".:lib/*" WorkflowTester
```

**In WorkflowTester menu:**
1. Choose **Option 3** (Monitor workflow_response_queue)
2. Wait for "Waiting for workflow responses..." message
3. Open **Terminal 4** (see below)

**Terminal 4: Send alerts**
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/WorkflowEngine
java -cp ".:lib/*" WorkflowTester
```

**In this second WorkflowTester:**
1. Choose **Option 1** or **Option 4** to send an alert
2. Watch Terminal 3 for workflow responses!

## Expected Output

### When sending an alert (Terminal 4):
```
✅ Sent alert #1
   Event ID: 7e8f9a1b-2c3d-4e5f-6a7b-8c9d0e1f2a3b
   Alert Type: ransomware_detection
   Severity: high
```

### When monitoring responses (Terminal 3):
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📨 WORKFLOW RESPONSE #1
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Message Type: workflow_command
Event ID: 7e8f9a1b-2c3d-4e5f-6a7b-8c9d0e1f2a3b
Source: WorkflowEngine

📦 Payload:
   Workflow Execution ID: exec-12345
   Workflow Name: General Ransomware Response
   Command: INITIATE_MITIGATION
   Action: ISOLATE
   Parameters: {
     "targetHost": "192.168.1.105",
     "action": "ISOLATE"
   }

📄 Full Message:
{
  "message_type": "workflow_command",
  "event_id": "7e8f9a1b-2c3d-4e5f-6a7b-8c9d0e1f2a3b",
  ...
}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

## What to Verify

✅ **Alert reaches ModuleRegistry**
- Check ModuleRegistry terminal for "📨 Received message type: alert"

✅ **ModuleRegistry broadcasts to both queues**
- Check ModuleRegistry terminal for "📤 Broadcasted alert"

✅ **WorkflowEngine receives and processes alert**
- Check WorkflowEngine terminal for "📨 Alert #X received"
- Should see "🔍 Looking for general ransomware workflow..."

✅ **Workflows execute and send commands**
- Check WorkflowEngine terminal for "✅ Executed workflow: [workflow name]"
- Should see "📤 Published to workflow_response_queue"

✅ **Commands appear in workflow_response_queue**
- Check WorkflowTester (Terminal 3) for "📨 WORKFLOW RESPONSE #X"
- Should see workflow commands with full details

## Troubleshooting

### No responses in workflow_response_queue
1. Verify WorkflowEngine is running
2. Check if alert is ransomware type
3. Verify workflow YAML files exist in `workflows/ransomware/`
4. Check WorkflowEngine logs for errors

### "Connection refused" error
1. Verify RabbitMQ is running at `192.168.1.8:5672`
2. Check username/password: `user/password`
3. Test connection: `telnet 192.168.1.8 5672`

### ModuleRegistry not receiving alerts
1. Verify ModuleRegistry is listening to `workflow_queue`
2. Check that WorkflowTester sends to correct queue
3. Verify queue exists in RabbitMQ management console

### WorkflowEngine not processing alerts
1. Check that alert has `message_type: "alert"`
2. Verify `alert_type` contains "ransomware"
3. Check workflow condition matching
4. Verify workflow files are valid YAML

## Message Format Reference

### Alert Message Structure
```json
{
  "message_type": "alert",
  "event_id": "uuid",
  "timestamp": "2025-10-29T04:00:00Z",
  "event_type": "alerts.host.wazuh",
  "source_module": "WorkflowTester",
  "payload": {
    "severity": "high",
    "alert_type": "ransomware_detection",
    "host_id": "host-192.168.1.105",
    "threat_score": 85,
    "signature": "Ransomware behavior detected",
    "source_ip": "192.168.1.105",
    ...
  }
}
```

### Workflow Command Structure (Expected Response)
```json
{
  "message_type": "workflow_command",
  "event_id": "same-as-alert-event-id",
  "timestamp": "2025-10-29T04:00:00Z",
  "event_type": "command.execute",
  "source_module": "WorkflowEngine",
  "payload": {
    "workflow_execution_id": "exec-xxxxx",
    "workflow_name": "General Ransomware Response",
    "command": "INITIATE_MITIGATION",
    "parameters": {
      "targetHost": "192.168.1.105",
      "action": "ISOLATE"
    }
  }
}
```

## Advanced Usage

### Testing Specific Workflows

To test a specific workflow, modify the alert in `createRansomwareAlert()` or `createDharmaFileEncryptionAlert()` to match your workflow's trigger conditions.

**Example: Test high-severity shell execution workflow**
```java
payload.put("severity", "high");
payload.put("signature", "Shell command execution");
payload.put("threat_score", 85);
```

### Monitoring Multiple Responses

If a single alert triggers multiple workflows, you'll see multiple responses in the monitor:
- General ransomware workflow response
- Specific workflow response(s)

Each response will be displayed separately with full details.

## Clean Up

To stop monitoring:
1. Press ENTER in Terminal 3
2. Wait for "✅ Stopped monitoring" message

To exit the tester:
- Choose Option 5 from the menu
- Or press CTRL+C

## Summary

The WorkflowTester provides a complete end-to-end test of:
1. ✅ Alert sending to ModuleRegistry
2. ✅ ModuleRegistry broadcasting
3. ✅ WorkflowEngine receiving and processing
4. ✅ Workflow execution
5. ✅ Command publishing to response queue
6. ✅ Response monitoring and display

Perfect for verifying your entire SOAR pipeline is working correctly!

