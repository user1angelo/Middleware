# ThreatContextStore Quick Start Guide

## Prerequisites Check

Before starting, ensure:
- ✅ PostgreSQL is running at `192.168.171.145:5432`
- ✅ RabbitMQ is running at `192.168.86.76:5672`
- ✅ Database schema is up-to-date (see below)
- ✅ Java 17+ is installed

## First-Time Setup

### 1. Update Database Schema (If Needed)

Run this on your PostgreSQL server to add missing columns:

```bash
# Connect to your PostgreSQL server at 192.168.171.145
ssh user@192.168.171.145

# Run migration
psql -U postgres -d wazuhdb << EOF
ALTER TABLE wazuh_alerts ADD COLUMN IF NOT EXISTS response_count INTEGER DEFAULT 0;
ALTER TABLE wazuh_alerts ADD COLUMN IF NOT EXISTS response_status VARCHAR(50) DEFAULT 'pending';
EOF
```

### 2. Verify Compilation

```bash
cd ~/Documents/GitHub/Middleware/ThreatContextStore
javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/*.java
```

## Running the System

### Start ThreatContextStore (Terminal 1)

```bash
cd ~/Documents/GitHub/Middleware/ThreatContextStore
java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain
```

You should see:
```
🚀 Starting ThreatContextStore Main Application
================================================
📡 Starting RabbitMQ Listener thread...
👁️  Starting File Watcher AlertProcessor thread...
✅ Connected to RabbitMQ at 192.168.86.76
⏳ Waiting for messages from queue: alerts_queue
```

**Keep this running!** This is your main application.

---

## Testing with TCSTester

### Send 10 Test Alerts (Terminal 2)

```bash
cd ~/Documents/GitHub/Middleware/ThreatContextStoreTester
java -cp ".:../ThreatContextStore/lib/*" TCSTester
```

Then:
1. Choose option: **1** (Send random alerts)
2. Choose option: **2** (Send fixed 10)

Watch Terminal 1 - you should see:
```
🔥 Stored alert: abc-123... | Severity: high
🔥 Stored alert: def-456... | Severity: medium
...
```

### Query Alerts (Terminal 2)

```bash
cd ~/Documents/GitHub/Middleware/ThreatContextStoreTester
java -cp ".:../ThreatContextStore/lib/*" TCSTester
```

Then:
1. Choose option: **2** (Query alerts)
2. Enter filter field: **severity**
3. Enter filter value: **high**
4. Add another filter?: **n**
5. Order by field: *[press Enter for default]*
6. Order direction: *[press Enter for default]*
7. Limit: **10** *[or press Enter]*

Watch Terminal 1 - you should see:
```
🔍 Processing query: query-xyz-789...
   SQL: SELECT * FROM wazuh_alerts WHERE payload->>'severity' = ? ORDER BY timestamp DESC LIMIT 10
   Found 5 results
✅ Sent 5 query responses
```

### Verify Query Responses

```bash
# Check the query_responses directory
ls -la ~/Documents/GitHub/Middleware/ThreatContextStore/query_responses/

# View a response file
cat ~/Documents/GitHub/Middleware/ThreatContextStore/query_responses/<some-event-id>.json
```

---

## Quick Verification Commands

### Check Database

```bash
# From any machine with PostgreSQL client
psql -h 192.168.171.145 -U postgres -d wazuhdb -c "
SELECT 
    message_type, 
    COUNT(*) as count 
FROM wazuh_alerts 
GROUP BY message_type;
"
```

Expected output:
```
 message_type | count 
--------------+-------
 alert        |    10
 query        |     1
```

### Check RabbitMQ Queues

```bash
# If you have rabbitmqadmin installed
rabbitmqadmin -H 192.168.86.76 -u guest -p guest list queues

# Or via web UI
# Open: http://192.168.86.76:15672
# Login: guest/guest
```

---

## Common Issues

### Issue: "Column response_count does not exist"

**Solution:** Run the database migration (see First-Time Setup above)

### Issue: Only 4 out of 10 messages processed

**Solution:** This was fixed! Make sure you recompiled RabbitMQListener.java with the latest changes.

### Issue: Timestamps are in UTC instead of Manila time

**Solution:** Recompile TCSTester with the latest changes:
```bash
cd ~/Documents/GitHub/Middleware/ThreatContextStoreTester
javac -cp "../ThreatContextStore/lib/*" TCSTester.java
```

### Issue: Can't find query responses

**Solution:** Check the correct location:
```bash
ls -la ~/Documents/GitHub/Middleware/ThreatContextStore/query_responses/
```

NOT in `ThreatContextStoreTester/query_responses/` (that's just a placeholder)

---

## Understanding the Message Flow

```
TCSTester → RabbitMQ (alerts_queue) → RabbitMQListener → PostgreSQL
                                              ↓
                                    (for queries only)
                                              ↓
                                       Execute Query
                                              ↓
                                   ┌──────────┴──────────┐
                                   ↓                     ↓
                        query_response_queue    query_responses/
                          (RabbitMQ)            (JSON files)
```

---

## Stopping the System

1. In Terminal 1 (ThreatContextStore): Press **CTRL+C**
   - You should see: `🛑 Shutdown signal received...`
   - Then: `✅ ThreatContextStore stopped gracefully`

2. TCSTester exits automatically after each operation

---

## Next Steps

- Try querying with multiple filters
- Try continuous alert sending (option 1 → 3)
- Check the database to see stored alerts
- Monitor RabbitMQ queue depth during load testing

---

## Quick Reference: All Commands

```bash
# Start ThreatContextStore
cd ~/Documents/GitHub/Middleware/ThreatContextStore
java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain

# Run TCSTester
cd ~/Documents/GitHub/Middleware/ThreatContextStoreTester
java -cp ".:../ThreatContextStore/lib/*" TCSTester

# Check query responses
ls -la ~/Documents/GitHub/Middleware/ThreatContextStore/query_responses/

# Query database
psql -h 192.168.171.145 -U postgres -d wazuhdb -c "SELECT COUNT(*) FROM wazuh_alerts;"
```

