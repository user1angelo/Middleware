# ThreatContextStore Debugging Guide

## Problem: Only 3 out of 10 messages were processed

This guide will help you diagnose why messages aren't being processed.

---

## Step 1: Check Database Schema

The most common issue is missing database columns. Verify your schema:

```bash
# Connect to PostgreSQL
psql -h 192.168.86.28 -U postgres -d wazuhdb

# Check table structure
\d wazuh_alerts
```

**Expected columns:**
- `event_id` (UUID, PRIMARY KEY)
- `message_type` (VARCHAR)
- `timestamp` (TIMESTAMPTZ)
- `event_type` (VARCHAR)
- `source_module` (VARCHAR)
- `payload` (JSONB)
- `response_count` (INTEGER) ← **Must exist!**
- `response_status` (VARCHAR) ← **Must exist!**

**If missing, run migration:**
```bash
psql -h 192.168.86.28 -U postgres -d wazuhdb << EOF
ALTER TABLE wazuh_alerts ADD COLUMN IF NOT EXISTS response_count INTEGER DEFAULT 0;
ALTER TABLE wazuh_alerts ADD COLUMN IF NOT EXISTS response_status VARCHAR(50) DEFAULT 'pending';
EOF
```

---

## Step 2: Watch Detailed Logs

The new logging shows **every single message** with full details.

### Start ThreatContextStore with logging:

```bash
cd ~/Documents/GitHub/Middleware/ThreatContextStore
java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain 2>&1 | tee logs.txt
```

This saves all output (including errors) to `logs.txt` for review.

### What to look for:

#### ✅ **Successful Processing:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📨 Message #1 received (deliveryTag=1, redelivered=false)
📄 Raw message length: 542 bytes
🏷️  Event ID: abc-123-def-456
🔖 Message Type: alert
➡️  Routing to: handleAlert()
   🔄 Calling dao.insertMessage()...
   ✓ Database insert successful
   🔥 Alert stored successfully | Event ID: abc-123... | Severity: high
✅ ACK sent for message #1 (deliveryTag=1)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

#### ❌ **Failed Processing:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📨 Message #2 received (deliveryTag=2, redelivered=false)
📄 Raw message length: 538 bytes
🏷️  Event ID: def-456-ghi-789
🔖 Message Type: alert
➡️  Routing to: handleAlert()
   🔄 Calling dao.insertMessage()...

❌❌❌ EXCEPTION in message #2 ❌❌❌
Error message: ERROR: column "response_count" does not exist
Exception type: org.postgresql.util.PSQLException

⚠️  This is the FIRST FAILURE (redelivered=false)
⚠️  REQUEUING message #2 (deliveryTag=2) for one retry
🔄 NACK sent (requeue=true) - message will be retried
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Step 3: Check RabbitMQ Queue Status

Use the diagnostic script:

```bash
cd ~/Documents/GitHub/Middleware
./check_queue_status.sh
```

**Expected output:**
```
Queue: alerts_queue
  Total messages:       0
  Ready:                0
  Unacknowledged:       0
  Active consumers:     1
```

**What each field means:**

- **Ready:** Messages waiting to be consumed
  - **High number = Problem!** Listener is not consuming messages fast enough
  
- **Unacknowledged:** Messages being processed but not yet ACK'd
  - **High number = Problem!** Messages are stuck in processing
  
- **Active consumers:** Number of listeners connected
  - **0 = Problem!** No listener is running
  - **Should be 1** when ThreatContextStore is running

---

## Step 4: Count Messages in Database

Compare what was sent vs. what was stored:

```bash
# Check total messages stored
psql -h 192.168.86.28 -U postgres -d wazuhdb -c "
SELECT 
    message_type, 
    COUNT(*) as count 
FROM wazuh_alerts 
GROUP BY message_type;
"
```

**Example output:**
```
 message_type | count 
--------------+-------
 alert        |     3    ← Should be 10!
 query        |     0
```

If count is less than expected, check logs for exceptions.

---

## Step 5: Common Issues and Solutions

### Issue 1: "Column response_count does not exist"

**Cause:** Database schema is outdated

**Solution:** Run the migration (see Step 1)

---

### Issue 2: Only 3-4 messages processed out of 10

**Possible causes:**

1. **Database schema issue** (most common)
   - Messages fail due to missing columns
   - Failed messages are requeued and block the queue
   - **Solution:** Run migration

2. **Duplicate event_id**
   - Messages with duplicate event_id are rejected
   - Check logs for "Duplicate key" errors
   - **Solution:** TCSTester generates unique UUIDs, so this is unlikely

3. **Connection timeout**
   - Database or RabbitMQ connection drops
   - Check logs for connection errors
   - **Solution:** Verify network connectivity

---

### Issue 3: Messages stuck in "Unacknowledged" state

**Cause:** ThreatContextStore crashed or was killed mid-processing

**Solution:** 
1. Stop ThreatContextStore completely
2. Wait 30 seconds for RabbitMQ to detect disconnection
3. Restart ThreatContextStore
4. Messages will be redelivered

---

### Issue 4: No messages being consumed at all

**Symptoms:**
- "Active consumers: 0" in queue status
- No log output after "⏳ Waiting for messages..."

**Possible causes:**
1. ThreatContextStore not running
2. Wrong queue name in config.properties
3. RabbitMQ not accessible

**Solution:**
```bash
# Verify ThreatContextStore is running
ps aux | grep ThreatContextStore

# Check network connectivity
ping 192.168.86.76

# Verify queue name
grep rabbitmq.queue.name ThreatContextStore/config.properties
```

---

## Step 6: Detailed Message Tracking

The new logging tracks every message. Here's what each counter means:

### Message Counter
```
📨 Message #1 received
📨 Message #2 received
📨 Message #3 received
```

- Sequential counter of all messages received
- Includes original messages AND retries
- If you send 10 messages but see 15 in logs, 5 were retried

### Delivery Tag
```
deliveryTag=1
deliveryTag=2
```

- RabbitMQ's internal message ID
- Unique per message per channel
- Used for ACK/NACK operations

### Redelivered Flag
```
redelivered=false  ← First time processing this message
redelivered=true   ← This is a retry
```

---

## Step 7: Full Debugging Checklist

Use this checklist to systematically debug:

- [ ] Database schema has `response_count` and `response_status` columns
- [ ] ThreatContextStore is running (check with `ps aux`)
- [ ] RabbitMQ is accessible (check with `ping 192.168.86.76`)
- [ ] Active consumers = 1 (check with `./check_queue_status.sh`)
- [ ] No messages stuck in "Ready" state
- [ ] No messages stuck in "Unacknowledged" state
- [ ] Logs show "✅ ACK sent" for each message
- [ ] No exceptions in logs
- [ ] Database count matches messages sent

---

## Step 8: Test with Verbose Logging

Send messages and watch logs in real-time:

### Terminal 1: Start ThreatContextStore
```bash
cd ~/Documents/GitHub/Middleware/ThreatContextStore
java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain
```

### Terminal 2: Send test messages
```bash
cd ~/Documents/GitHub/Middleware/ThreatContextStoreTester
java -cp ".:../ThreatContextStore/lib/*" TCSTester
# Choose: 1 → 2 (send 10 alerts)
```

### Terminal 3: Watch database in real-time
```bash
watch -n 1 'psql -h 192.168.86.28 -U postgres -d wazuhdb -c "SELECT COUNT(*) FROM wazuh_alerts WHERE message_type='\''alert'\'';"'
```

**Expected behavior:**
- Terminal 1: Shows 10 message processing logs
- Terminal 2: Shows "📤 Sent alert 1/10" through "📤 Sent alert 10/10"
- Terminal 3: Count increases from 0 to 10

---

## Step 9: Analyze Failed Messages

If messages are failing, look for patterns:

```bash
# Search for all exceptions in logs
grep -A 10 "❌❌❌ EXCEPTION" logs.txt

# Count how many retries occurred
grep "redelivered=true" logs.txt | wc -l

# Count successful ACKs
grep "✅ ACK sent" logs.txt | wc -l

# Count failed NACKs
grep "NACK sent" logs.txt | wc -l
```

---

## Quick Fix: Reset Everything

If all else fails, reset the system:

```bash
# 1. Stop ThreatContextStore
# Press CTRL+C in the terminal running it

# 2. Purge RabbitMQ queue (CAUTION: deletes all queued messages)
curl -u guest:guest -X DELETE http://192.168.86.76:15672/api/queues/%2F/alerts_queue/contents

# 3. Clear database (CAUTION: deletes all data)
psql -h 192.168.86.28 -U postgres -d wazuhdb -c "TRUNCATE TABLE wazuh_alerts;"

# 4. Run migration
psql -h 192.168.86.28 -U postgres -d wazuhdb -f ThreatContextStore/migration_add_query_columns.sql

# 5. Restart ThreatContextStore
cd ThreatContextStore
java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain

# 6. Test with 5 messages first
cd ../ThreatContextStoreTester
java -cp ".:../ThreatContextStore/lib/*" TCSTester
# Choose: 1 → 1 → 5
```

---

## Need More Help?

If you've tried all the steps above and still have issues:

1. **Capture full logs:**
   ```bash
   java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain 2>&1 | tee full_debug.log
   ```

2. **Check the logs for:**
   - Exception types and messages
   - Message counters (how many received vs processed)
   - Redelivery patterns (same message retried multiple times?)

3. **Share specific error messages** for targeted troubleshooting

