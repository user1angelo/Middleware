# TCSTester - Ransomware-Only Alert Generation

## Changes Made ✅

TCSTester has been modified to **only generate ransomware alerts** for focused WorkflowEngine testing.

### What Changed

#### 1. Alert Types (Ransomware Only)
**Before:**
```java
private static final String[] ALERT_TYPES = {
    "ransomware_detection", "malware_execution", "intrusion_attempt", 
    "ddos_attack", "data_exfiltration", "privilege_escalation",
    "brute_force_attack", "sql_injection", "xss_attack", "command_injection"
};
```

**After:**
```java
private static final String[] ALERT_TYPES = {
    "ransomware_detection", "ransomware_encryption", "ransomware_propagation"
};
```

#### 2. Severities (High Risk Only)
**Before:**
```java
private static final String[] SEVERITIES = {"high", "medium", "low", "critical"};
```

**After:**
```java
private static final String[] SEVERITIES = {"high", "critical"};
```

#### 3. Signatures (Ransomware Specific)
**Before:**
- Generic security signatures (SQL injection, XSS, brute force, etc.)

**After:**
- Ransomware-specific signatures only:
  - "Suspicious file encryption activity"
  - "Mass file modification detected"
  - "Ransomware encryption pattern"
  - "File system lockdown attempt"
  - "Crypto-locker behavior detected"
  - "Ransomware process execution"
  - "Volume shadow copy deletion"
  - "Backup deletion attempt"
  - "Extension modification (.encrypted)"
  - "Shell command execution"

#### 4. Threat Score (High Range)
**Before:**
```java
payload.put("threat_score", random.nextInt(100)); // 0-99
```

**After:**
```java
payload.put("threat_score", 70 + random.nextInt(31)); // 70-100
```

#### 5. Matched Rules (Ransomware Specific)
**Before:**
```java
payload.put("matched_rule", "rule_" + random.nextInt(1000));
```

**After:**
```java
payload.put("matched_rule", "ransomware_rule_" + random.nextInt(100));
```

---

## Why These Changes?

### 1. **Focused Testing**
- WorkflowEngine is designed for ransomware response
- No need to test with unrelated attack types
- Easier to verify workflow matching logic

### 2. **Guaranteed Workflow Matching**
All generated alerts will now:
- ✅ Always trigger the **general ransomware workflow**
- ✅ Always match **high-severity conditions** (severity = high/critical)
- ✅ Always meet **threat_score ≥ 70** threshold
- ✅ Have ransomware-specific signatures and alert types

### 3. **Consistent Test Data**
- Every alert is relevant to ransomware scenarios
- No "false negatives" from unrelated alert types
- Clean, predictable workflow execution patterns

---

## Sample Generated Alert

```json
{
  "message_type": "alert",
  "event_id": "12345678-1234-5678-1234-567890abcdef",
  "timestamp": "2025-10-07T19:38:24+08:00",
  "event_type": "alerts.host.wazuh",
  "source_module": "TCSTester",
  "payload": {
    "severity": "high",                           ← Only "high" or "critical"
    "alert_type": "ransomware_detection",         ← Only ransomware types
    "signature_id": "9203456",
    "signature": "Ransomware encryption pattern",  ← Ransomware-specific
    "host_id": "host-192.168.125.43",
    "source_ip": "192.168.45.67",
    "destination_ip": "10.0.34.12",
    "protocol": "TCP",
    "process": "/tmp/process234",
    "file_path": "/home/user5/file789.dat",
    "threat_score": 87,                           ← Always 70-100
    "matched_rule": "ransomware_rule_42"          ← Ransomware-specific rule
  }
}
```

---

## Expected Workflow Behavior

When you run TCSTester and send 10 alerts:

### General Workflow
- **Executes**: 10 times (once per alert)
- **Reason**: Always runs for any ransomware alert
- **Steps**: Log event, Isolate host, Alert SOC

### Specific Workflow (shell_execution_high_severity.yml)
- **Executes**: ~10 times (all alerts meet conditions)
- **Reason**: All alerts have:
  - severity = "high" or "critical" ✓
  - threat_score ≥ 70 ✓
  - Ransomware-related signature ✓
- **Steps**: Additional logging, Quarantine, Terminate process, Collect forensics, Escalate

### Total Workflow Responses Expected
- **Minimum**: 20 messages (10 general + 10 specific)
- **Both workflows execute for every alert**

---

## How to Test

### 1. Start WorkflowEngine
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/WorkflowEngine
java -cp "out:lib/*" com.yourorg.workflow.WorkflowEngineMain
```

### 2. Send Ransomware Alerts (New Terminal)
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/ThreatContextStoreTester
java -cp ".:../ThreatContextStore/lib/*" TCSTester

# Choose: 1 → 2 (send 10 ransomware alerts)
```

### 3. Observe Workflow Execution
Watch WorkflowEngine logs for:
- ✅ All 10 alerts received
- ✅ 10 general workflow executions
- ✅ 10 specific workflow executions (conditions matched)
- ✅ 20 workflow_response messages sent

---

## Compilation Status

✅ **COMPILED SUCCESSFULLY**

```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/ThreatContextStoreTester
javac -cp ".:../ThreatContextStore/lib/*" TCSTester.java
# Exit code: 0
```

---

## Files Modified

- `TCSTester.java` - Lines 33-44 (alert data pools)
- `TCSTester.java` - Lines 210-212 (threat_score and matched_rule)

---

## Revert Instructions

If you need to revert to mixed alert types, you can restore the original arrays from git history or modify the constants back to include other alert types.

---

**Status: ✅ READY FOR RANSOMWARE-FOCUSED TESTING**

All alerts generated by TCSTester will now be ransomware-related with high severity and high threat scores, guaranteeing workflow execution in the WorkflowEngine.

