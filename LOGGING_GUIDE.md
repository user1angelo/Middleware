# ThreatContextStore Logging Guide

## Overview

ThreatContextStore now has **comprehensive logging** that shows exactly what happens to every single message. This helps debug issues like "only 3 out of 10 messages were processed."

**Note on hosts/paths:** Wherever you see an IP address or a `~/Documents/...` path in older examples, treat it as an environment-specific placeholder. The authoritative values are:
- `ThreatContextStore/config.properties` (or the defaults in `ThreatContextStore/src/main/java/com/yourorg/middleware/ConfigLoader.java`)
- Your local repo path

---

## What Gets Logged

### For Every Message:

1. **Message Receipt**
   - Message counter (#1, #2, #3...)
   - RabbitMQ delivery tag
   - Redelivered flag (is this a retry?)
   
2. **Message Content**
   - Raw message size in bytes
   - Event ID
   - Message type (alert, query, query_response)
   
3. **Processing Steps**
   - Which handler is called
   - Database operations
   - Success/failure status
   
4. **Acknowledgment**
   - ACK sent (message processed successfully)
   - NACK sent (message failed, with retry/discard decision)

---

## Log Format Examples

### ✅ Successful Alert Processing

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📨 Message #1 received (deliveryTag=1, redelivered=false)
📄 Raw message length: 542 bytes
🏷️  Event ID: 4e5344ac-7df0-4cc2-9714-2af6ba41a983
🔖 Message Type: alert
➡️  Routing to: handleAlert()
   🔄 Calling dao.insertMessage()...
   ✓ Database insert successful
   🔥 Alert stored successfully | Event ID: 4e5344ac... | Severity: high
✅ ACK sent for message #1 (deliveryTag=1)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Interpretation:**
- Message #1 was successfully received
- It's an alert message (not a retry)
- Database insert succeeded
- Message was acknowledged to RabbitMQ

---

### ❌ Failed Processing (First Attempt)

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📨 Message #2 received (deliveryTag=2, redelivered=false)
📄 Raw message length: 538 bytes
🏷️  Event ID: 8f2a5b91-3c4d-4e5f-b6a7-1d8e9f0a2b3c
🔖 Message Type: alert
➡️  Routing to: handleAlert()
   🔄 Calling dao.insertMessage()...

❌❌❌ EXCEPTION in message #2 ❌❌❌
Error message: ERROR: column "response_count" does not exist
  Position: 98
Exception type: org.postgresql.util.PSQLException

Full stack trace:
org.postgresql.util.PSQLException: ERROR: column "response_count" does not exist
  Position: 98
	at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(...)
	at org.postgresql.core.v3.QueryExecutorImpl.processResults(...)
	...

⚠️  This is the FIRST FAILURE (redelivered=false)
⚠️  REQUEUING message #2 (deliveryTag=2) for one retry
🔄 NACK sent (requeue=true) - message will be retried
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Interpretation:**
- Message #2 failed during database insert
- Problem: Missing `response_count` column in database
- This is the first failure, so message will be retried
- Message is still in RabbitMQ queue

---

### ❌ Failed Processing (Retry - Final Discard)

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📨 Message #3 received (deliveryTag=3, redelivered=true)
📄 Raw message length: 538 bytes
🏷️  Event ID: 8f2a5b91-3c4d-4e5f-b6a7-1d8e9f0a2b3c
🔖 Message Type: alert
➡️  Routing to: handleAlert()
   🔄 Calling dao.insertMessage()...

❌❌❌ EXCEPTION in message #3 ❌❌❌
Error message: ERROR: column "response_count" does not exist
  Position: 98
Exception type: org.postgresql.util.PSQLException

Full stack trace:
...

⚠️  This message ALREADY FAILED ONCE (redelivered=true)
⚠️  DISCARDING message #3 (deliveryTag=3) to prevent infinite loop
🗑️  NACK sent (requeue=false) - message discarded
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Interpretation:**
- Same message (#2) is being retried
- `redelivered=true` means this is attempt #2
- Still failing with same error
- Message is permanently discarded to prevent infinite loops
- **Fix the database schema issue to prevent this!**

---

### ✅ Query Processing

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📨 Message #11 received (deliveryTag=11, redelivered=false)
📄 Raw message length: 395 bytes
🏷️  Event ID: query-a1b2c3d4-e5f6-7890-abcd-ef1234567890
🔖 Message Type: query
➡️  Routing to: handleQuery()
🔍 Processing query: query-a1b2c3d4...
   SQL: SELECT * FROM wazuh_alerts WHERE payload->>'severity' = ? ORDER BY timestamp DESC LIMIT 10
   Found 5 results
✅ Sent 5 query responses
✅ ACK sent for message #11 (deliveryTag=11)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Interpretation:**
- Query message received and processed
- SQL was generated from JSON query
- 5 matching alerts found
- Each result sent to `query_response_queue`
- Each result saved to `query_responses/{event_id}.json`

---

## Understanding Message Counters

### Message Counter vs. Messages Sent

**If you send 10 messages:**

**Scenario 1: All successful**
```
📨 Message #1 received  ← Original
📨 Message #2 received  ← Original
📨 Message #3 received  ← Original
...
📨 Message #10 received ← Original
```
Total: 10 log entries, 10 ACKs

**Scenario 2: Some failures with retries**
```
📨 Message #1 received (redelivered=false)  ← Original, success
📨 Message #2 received (redelivered=false)  ← Original, FAILED
📨 Message #3 received (redelivered=false)  ← Original, FAILED
📨 Message #4 received (redelivered=true)   ← Retry of #2, FAILED AGAIN → Discarded
📨 Message #5 received (redelivered=true)   ← Retry of #3, FAILED AGAIN → Discarded
...
```
Total: More than 10 log entries (includes retries)

---

## What Each Symbol Means

| Symbol | Meaning |
|--------|---------|
| 📨 | Message received from RabbitMQ |
| 📄 | Raw message information |
| 🏷️ | Event ID |
| 🔖 | Message type |
| ➡️ | Routing decision |
| 🔄 | Processing step |
| ✓ | Success indicator |
| 🔥 | Alert stored |
| 🔍 | Query processing |
| 📥 | Query response received |
| ✅ | ACK sent (success) |
| ❌ | Exception occurred |
| ⚠️ | Warning or important note |
| 🔄 | NACK sent (requeue=true) |
| 🗑️ | NACK sent (requeue=false, discarded) |
| ━━━ | Visual separator |

---

## Common Log Patterns

### Pattern 1: Everything Working
```
📨 Message #1 received → ✅ ACK sent
📨 Message #2 received → ✅ ACK sent
📨 Message #3 received → ✅ ACK sent
...
```
**Status:** ✅ Healthy

---

### Pattern 2: Database Schema Issue
```
📨 Message #1 received → ❌ EXCEPTION → 🔄 Requeuing
📨 Message #2 received → ❌ EXCEPTION → 🔄 Requeuing
📨 Message #3 received (redelivered=true) → ❌ EXCEPTION → 🗑️ Discarded
📨 Message #4 received (redelivered=true) → ❌ EXCEPTION → 🗑️ Discarded
```
**Status:** ❌ Database schema needs migration
**Fix:** Run `ThreatContextStore/migration_add_query_columns.sql`

---

### Pattern 3: Duplicate Event IDs
```
📨 Message #1 received → ✅ ACK sent
📨 Message #2 received → ❌ EXCEPTION (Duplicate key) → 🔄 Requeuing
📨 Message #3 received (redelivered=true) → ❌ EXCEPTION → 🗑️ Discarded
```
**Status:** ⚠️ Duplicate event_id detected
**Fix:** Check message generator for unique UUID creation

---

### Pattern 4: Connection Issues
```
📨 Message #1 received → ❌ EXCEPTION (Connection refused)
📨 Message #2 received → ❌ EXCEPTION (Connection refused)
...
```
**Status:** ❌ Database or RabbitMQ connection lost
**Fix:** Check network connectivity and service status

---

## Analyzing Logs

### Count Total Messages Received
```bash
grep "📨 Message #" logs.txt | wc -l
```

### Count Successful Acknowledgments
```bash
grep "✅ ACK sent" logs.txt | wc -l
```

### Count Failed Messages (First Attempt)
```bash
grep "redelivered=false" logs.txt | grep "❌❌❌ EXCEPTION" | wc -l
```

### Count Retried Messages
```bash
grep "redelivered=true" logs.txt | wc -l
```

### Count Discarded Messages
```bash
grep "🗑️  NACK sent (requeue=false)" logs.txt | wc -l
```

### Find All Unique Exceptions
```bash
grep "Exception type:" logs.txt | sort | uniq
```

---

## Troubleshooting Tips

### If you see fewer ACKs than messages sent:

1. **Check for exceptions:**
   ```bash
   grep "❌❌❌ EXCEPTION" logs.txt
   ```

2. **Look for the error type:**
   ```bash
   grep "Error message:" logs.txt
   ```

3. **Check if messages were discarded:**
   ```bash
   grep "DISCARDING" logs.txt
   ```

---

### If messages keep retrying:

**Look for this pattern:**
```
redelivered=false → EXCEPTION → Requeuing
redelivered=true  → EXCEPTION → Discarding
```

This means:
- Message failed twice
- Underlying issue was NOT fixed between retries
- Fix the root cause (usually database schema)

---

### If no messages appear in logs:

1. **Check if listener is connected:**
   ```bash
   # If you have bash available (WSL / Git Bash):
   # ./check_queue_status.sh
   # Otherwise, use RabbitMQ Management UI / API.
   ```
   
2. **Verify Active consumers = 1**

3. **Check RabbitMQ connectivity:**
   ```bash
   ping <RABBITMQ_HOST>
   ```

---

## Log File Management

### Save logs to file:
```bash
# Windows (PowerShell)
cd ThreatContextStore
java -cp "out;lib/*" com.yourorg.middleware.ThreatContextStoreMain *>&1 | Tee-Object -FilePath logs.txt

# Linux/macOS
# cd ThreatContextStore
# java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain 2>&1 | tee logs.txt
```

### Save only errors:
```bash
# Linux/macOS (bash process substitution)
# java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain 2>&1 | tee >(grep -E "❌|⚠️" > errors.txt)

# Windows (PowerShell)
# java -cp "out;lib/*" com.yourorg.middleware.ThreatContextStoreMain *>&1 | Tee-Object -FilePath logs.txt
# Select-String -Path logs.txt -Pattern "❌","⚠️" | Set-Content errors.txt
```

### Watch logs in real-time:
```bash
# Windows (PowerShell)
Get-Content -Path logs.txt -Wait

# Linux/macOS
# tail -f logs.txt
```

---

## Performance Impact

The enhanced logging adds:
- ~5-10ms per message (negligible for normal loads)
- Minimal memory overhead
- Clear visibility into system behavior

**Trade-off:** Small performance cost for huge debugging benefit!

---

## When to Use Detailed Logging

**Always use it when:**
- Testing new features
- Debugging message processing issues
- Monitoring production systems
- Investigating missing messages

**Consider disabling if:**
- Processing >1000 messages/sec (performance-critical)
- Running in production with no issues
- Log files become too large

---

## Next Steps

1. **Start ThreatContextStore with logging:**
   ```bash
   cd ThreatContextStore

   # Windows (PowerShell)
   java -cp "out;lib/*" com.yourorg.middleware.ThreatContextStoreMain *>&1 | Tee-Object -FilePath logs.txt

   # Linux/macOS
   # java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain 2>&1 | tee logs.txt
   ```

2. **Send test messages:**
   ```bash
   cd ThreatContextStoreTester
   
   # Windows
   java -cp ".;../ThreatContextStore/lib/*" TCSTester

   # Linux/macOS
   # java -cp ".:../ThreatContextStore/lib/*" TCSTester
   ```

3. **Review logs:**
   - Look for ❌ exceptions
   - Count ✅ ACKs vs messages sent
   - Check for redelivered=true entries

4. **Fix any issues found**

5. **Re-test until all messages show ✅ ACK sent**

