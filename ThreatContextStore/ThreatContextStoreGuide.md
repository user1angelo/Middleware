# ThreatContextStore Guide

A comprehensive guide for system administrators and developers working with the ThreatContextStore middleware system.

## Table of Contents
- [Overview](#overview)
- [Architecture](#architecture)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Components](#components)
- [Operations](#operations)
- [Use Cases](#use-cases)
- [Troubleshooting](#troubleshooting)
- [Development](#development)

---

## Overview

### What is ThreatContextStore?

ThreatContextStore is a Java-based middleware system that bridges RabbitMQ message queues with PostgreSQL for security alert processing. It provides a message-type based routing system that handles alerts, queries, and query responses.

**Key Features:**
- **Three message types**: `alert`, `query`, `query_response`
- **Dynamic query translation**: JSON queries converted to SQL
- **Dual-queue system**: Separate queues for alerts and query responses
- **UUID-based event tracking**: Proper UUID support for event_id
- **Query execution tracking**: Stores query status and response counts
- **Reliable message processing** with acknowledgment-based delivery
- **Flexible JSONB storage** for complex alert structures
- **Duplicate detection** using event IDs
- **Cross-platform compatibility** (Linux, macOS, Windows)
- **Centralized configuration** via properties file

**Use Cases:**
- Centralized security alert storage and analysis
- SIEM data pipeline component with dynamic querying
- Threat intelligence aggregation
- Security event correlation and querying
- Real-time query response distribution

---

## Architecture

### High-Level Data Flow

```
┌─────────────────┐
│  Alert Sources  │
│  (Wazuh, etc.)  │
└────────┬────────┘
         │
         v
┌─────────────────┐      ┌──────────────────┐      ┌─────────────────┐
│ AlertProcessor  │─────>│  RabbitMQ Queue  │─────>│ RabbitMQListener│
│  (JSON Files)   │      │  (alerts_queue)  │      │  or             │
└─────────────────┘      └──────────────────┘      │ ListenerWorker  │
                                                    └────────┬────────┘
                                                             │
                                                             v
                                                    ┌─────────────────┐
                                                    │   PostgreSQL    │
                                                    │   (wazuhdb)     │
                                                    └────────┬────────┘
                                                             │
                                                             v
                                                    ┌─────────────────┐
                                                    │   QueryDemo     │
                                                    │ (Export/Query)  │
                                                    └─────────────────┘
```

### Component Responsibilities

| Component | Role | Message Guarantee |
|-----------|------|-------------------|
| **AlertProcessor** | Reads JSON files and publishes to RabbitMQ | Fire-and-forget |
| **RabbitMQListener** | Primary consumer with error handling | At-least-once (manual ACK) |
| **ListenerWorker** | Simple consumer for testing | At-most-once (auto ACK) |
| **WazuhAlertDao** | Database operations with duplicate detection | Idempotent writes |
| **QueryDemo** | Query and export alerts by severity | Read-only |
| **ConfigLoader** | Centralized configuration management | N/A |

### Database Schema

**Table: `wazuh_alerts`**

```sql
CREATE TABLE wazuh_alerts (
    log_id TEXT PRIMARY KEY,        -- Composite key: timestamp_eventType_UUID
    event_id TEXT NOT NULL,         -- Original event UUID (for deduplication)
    timestamp TIMESTAMPTZ NOT NULL, -- Alert timestamp with timezone
    event_type TEXT NOT NULL,       -- Event classification (e.g., "alerts.host.wazuh")
    source_module TEXT NOT NULL,    -- Source identifier (e.g., "WazuhConnector")
    payload JSONB NOT NULL          -- Full alert data as flexible JSON
);
```

**Key Design Decisions:**
- **JSONB payload**: Supports flexible alert structures without schema changes
- **event_id deduplication**: Prevents duplicate alerts from being stored
- **TIMESTAMPTZ**: Preserves timezone information for global deployments
- **No auto-increment ID**: Uses composite log_id for better traceability

---

## Quick Start

### Prerequisites

**Software Requirements:**
- Java 17 or higher
- PostgreSQL 17 (accessible at configured host)
- RabbitMQ server (accessible at configured host)

**JAR Dependencies** (in `lib/` directory):
- `postgresql-42.7.7.jar` - PostgreSQL JDBC driver
- `amqp-client-5.26.0.jar` - RabbitMQ client
- `json-20231013.jar` - JSON processing
- `slf4j-api-2.0.17.jar` & `slf4j-simple-2.0.17.jar` - Logging

### 5-Minute Setup

```bash
# 1. Navigate to ThreatContextStore
cd ThreatContextStore

# 2. Create configuration
cp config.properties.example config.properties
# Edit config.properties with your settings

# 3. Initialize database
psql -U postgres -h 192.168.171.145 -f schema.sql

# 4. Compile the project
javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/*.java

# 5. Start the listener (in one terminal)
java -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener

# 6. Process some alerts (in another terminal)
java -cp "out:lib/*" com.yourorg.middleware.AlertProcessor

# 7. Query results
java -cp "out:lib/*" com.yourorg.middleware.QueryDemo high
```

---

## Configuration

### Configuration File: `config.properties`

Create from template:
```bash
cp config.properties.example config.properties
```

### Configuration Options

#### Database Settings
```properties
db.host=192.168.171.145        # PostgreSQL server hostname/IP
db.port=5432                  # PostgreSQL port
db.name=wazuhdb              # Database name
db.user=postgres             # Database username
db.password=postgres         # Database password
```

#### RabbitMQ Settings
```properties
rabbitmq.host=192.168.86.76  # RabbitMQ server hostname/IP
rabbitmq.port=5672           # RabbitMQ AMQP port
rabbitmq.user=guest          # RabbitMQ username
rabbitmq.password=guest      # RabbitMQ password
rabbitmq.queue.name=alerts_queue  # Queue name for alerts
```

#### File Paths
```properties
paths.messages=messages      # Input directory for JSON alert files
paths.output=output          # Output directory for exported queries
```

### Configuration Precedence

1. **config.properties** (if exists) - highest priority
2. **Hardcoded defaults** (in ConfigLoader.java) - fallback

**Note:** The application will run with defaults if `config.properties` is missing, but creating the file is strongly recommended.

---

## Components

### 1. ConfigLoader

**Purpose:** Centralized configuration management

**Features:**
- Reads `config.properties` on startup
- Provides type-safe getters (String, int, etc.)
- Falls back to defaults if file is missing
- Single source of truth for all components

**Usage Example:**
```java
String dbUrl = ConfigLoader.getDbUrl();
int rabbitPort = ConfigLoader.getRabbitMqPort();
```

### 2. AlertProcessor

**Purpose:** Publishes JSON alert files to RabbitMQ

**Workflow:**
1. Reads all `.json` files from `messages/` directory
2. Parses each file as JSON
3. Publishes to configured RabbitMQ queue
4. Reports success/failure for each file

**When to Use:**
- Batch processing historical alerts
- Testing the pipeline with sample data
- Replaying alerts from backups

**Run:**
```bash
java -cp "out:lib/*" com.yourorg.middleware.AlertProcessor
```

**Expected Output:**
```
✅ Connected to RabbitMQ queue: alerts_queue
✅ Sent alert_1.json to RabbitMQ.
✅ Sent alert_2.json to RabbitMQ.
...
```

### 3. RabbitMQListener

**Purpose:** Primary consumer for production use

**Features:**
- **Manual acknowledgment**: ACK on success, NACK on failure
- **Payload flattening**: Extracts nested payload to root level
- **Error handling**: Logs errors and requeues failed messages
- **Graceful shutdown**: Properly closes connections on SIGTERM
- **Prefetch=1**: Processes one message at a time

**Workflow:**
1. Connects to RabbitMQ queue
2. Waits for incoming messages
3. For each message:
   - Parse JSON
   - Flatten nested payload
   - Insert into PostgreSQL (with duplicate check)
   - ACK message if successful
   - NACK and requeue if failed
4. Runs indefinitely until interrupted

**When to Use:**
- Production deployments
- When message reliability is critical
- When you need error recovery

**Run:**
```bash
java -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener
```

**Expected Output:**
```
✅ Connected to RabbitMQ at 192.168.86.76
⏳ Waiting for messages from queue: alerts_queue
   Press CTRL+C to exit.

🔥 Inserted alert: 9201001 | Severity: high
🔥 Inserted alert: 9201002 | Severity: medium
...
```

### 4. ListenerWorker

**Purpose:** Simplified consumer for testing

**Features:**
- **Auto acknowledgment**: Messages are ACKed immediately
- **No error recovery**: Failed messages are lost
- **Simpler code**: Easier to understand for learning

**When to Use:**
- Development and testing
- Learning the codebase
- Non-critical alert processing

**When NOT to Use:**
- Production environments
- When you can't afford to lose messages

**Run:**
```bash
java -cp "out:lib/*" com.yourorg.middleware.ListenerWorker
```

### 5. WazuhAlertDao

**Purpose:** Data Access Object for PostgreSQL operations

**Key Methods:**

**insertAlert(JSONObject alert)**
- Checks for duplicate using `event_id`
- Inserts alert if not already present
- Extracts: event_id, timestamp, event_type, source_module, payload
- Thread-safe (uses connection per operation)

**queryBySeverityList(String severity)**
- Queries alerts by severity from JSONB payload
- Returns List of JSONObject
- Ordered by timestamp DESC

**Duplicate Detection Logic:**
```java
// Check if event_id already exists
SELECT 1 FROM wazuh_alerts WHERE event_id = ?;

// If exists: skip insertion
// If not exists: proceed with INSERT
```

### 6. QueryDemo

**Purpose:** Export and optionally republish alerts by severity

**Workflow:**
1. Query PostgreSQL for alerts matching severity
2. Create `output/` directory if needed
3. Export each alert as `{event_id}.json`
4. Optionally republish to RabbitMQ (if available)
5. Report count of exported alerts

**Usage:**
```bash
# Export high severity alerts
java -cp "out:lib/*" com.yourorg.middleware.QueryDemo high

# Export medium severity alerts
java -cp "out:lib/*" com.yourorg.middleware.QueryDemo medium

# Export all critical alerts
java -cp "out:lib/*" com.yourorg.middleware.QueryDemo critical
```

**Output:**
- Files written to `output/{event_id}.json`
- Pretty-printed JSON (indentation: 4 spaces)

**Optional RabbitMQ Republishing:**
If RabbitMQ is available, QueryDemo will also republish alerts to the queue. This is useful for:
- Reprocessing alerts through updated logic
- Migrating alerts to a different queue
- Testing with production-like data

---

## Operations

### Starting the System

**Recommended Startup Order:**

1. **Verify infrastructure:**
   ```bash
   # Check PostgreSQL
  psql -U postgres -h 192.168.171.145 -c "\l" | grep wazuhdb
   
   # Check RabbitMQ
   curl -u guest:guest http://192.168.86.76:15672/api/overview
   ```

2. **Start listener first:**
   ```bash
   # In terminal 1
   cd ThreatContextStore
   java -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener
   ```

3. **Then start producers:**
   ```bash
   # In terminal 2
   cd ThreatContextStore
   java -cp "out:lib/*" com.yourorg.middleware.AlertProcessor
   ```

### Stopping the System

**Graceful Shutdown:**
- Press `CTRL+C` in the listener terminal
- RabbitMQListener will close connections gracefully
- In-flight messages will be requeued automatically

**Forceful Shutdown:**
```bash
# Find Java processes
ps aux | grep middleware

# Kill specific process
kill -9 <PID>
```

### Monitoring Operations

**Check Queue Depth:**
```bash
# Via RabbitMQ management API
curl -u guest:guest http://192.168.86.76:15672/api/queues/%2F/alerts_queue

# Via rabbitmqctl (if you have server access)
rabbitmqctl list_queues name messages messages_ready messages_unacknowledged
```

**Check Database Stats:**
```sql
-- Count total alerts
SELECT COUNT(*) FROM wazuh_alerts;

-- Count by severity
SELECT payload->>'severity' AS severity, COUNT(*) 
FROM wazuh_alerts 
GROUP BY payload->>'severity';

-- Recent alerts (last hour)
SELECT COUNT(*) FROM wazuh_alerts 
WHERE timestamp > NOW() - INTERVAL '1 hour';

-- Check for duplicates (should be 0)
SELECT event_id, COUNT(*) 
FROM wazuh_alerts 
GROUP BY event_id 
HAVING COUNT(*) > 1;
```

**View Logs:**
```bash
# If running in foreground, logs appear in terminal
# If running as service, check service logs

# SLF4J simple logger outputs to stderr by default
```

---

## Use Cases

### Use Case 1: Processing New Alerts

**Scenario:** You have new alert JSON files to process

**Steps:**
1. Place JSON files in `messages/` directory
2. Ensure RabbitMQListener is running
3. Run AlertProcessor:
   ```bash
   java -cp "out:lib/*" com.yourorg.middleware.AlertProcessor
   ```
4. Verify insertion:
   ```bash
  psql -U postgres -h 192.168.171.145 -d wazuhdb -c "SELECT COUNT(*) FROM wazuh_alerts;"
   ```

### Use Case 2: Finding High-Severity Threats

**Scenario:** Security team needs all high-severity alerts for analysis

**Steps:**
1. Export to JSON files:
   ```bash
   java -cp "out:lib/*" com.yourorg.middleware.QueryDemo high
   ```
2. Review files in `output/` directory
3. Or query directly:
   ```sql
  psql -U postgres -h 192.168.171.145 -d wazuhdb -c \
   "SELECT event_id, timestamp, payload->>'alert_type', payload->>'signature' 
    FROM wazuh_alerts 
    WHERE payload->>'severity' = 'high' 
    ORDER BY timestamp DESC 
    LIMIT 20;"
   ```

### Use Case 3: Investigating Specific Alert Types

**Scenario:** Investigate all ransomware detection alerts

**SQL Query:**
```sql
SELECT 
    event_id,
    timestamp,
    payload->>'host_id' AS host,
    payload->>'process' AS process,
    payload->>'file_path' AS affected_file,
    payload->>'threat_score' AS score
FROM wazuh_alerts
WHERE payload->>'alert_type' = 'ransomware_detection'
ORDER BY timestamp DESC;
```

### Use Case 4: Correlation Analysis

**Scenario:** Find alerts from the same host in the last 24 hours

**SQL Query:**
```sql
SELECT 
    COUNT(*) as alert_count,
    payload->>'host_id' AS host,
    payload->>'severity' AS severity
FROM wazuh_alerts
WHERE timestamp > NOW() - INTERVAL '24 hours'
GROUP BY payload->>'host_id', payload->>'severity'
HAVING COUNT(*) > 5
ORDER BY alert_count DESC;
```

### Use Case 5: Network-Based Threat Hunting

**Scenario:** Find all alerts involving a specific IP address

**SQL Query:**
```sql
SELECT *
FROM wazuh_alerts
WHERE 
    payload->>'source_ip' = '192.168.1.101'
    OR payload->>'destination_ip' = '192.168.1.101'
ORDER BY timestamp DESC;
```

---

## Troubleshooting

### Common Issues

#### Issue: Compilation Fails

**Symptoms:**
```
error: package com.rabbitmq.client does not exist
```

**Causes:**
- Missing JAR files in `lib/` directory
- Incorrect classpath

**Solutions:**
```bash
# Verify JARs exist
ls -lh lib/

# Re-download missing JARs
# postgresql: https://jdbc.postgresql.org/download.html
# rabbitmq: https://repo1.maven.org/maven2/com/rabbitmq/amqp-client/5.26.0/
# json: https://repo1.maven.org/maven2/org/json/json/20231013/
# slf4j: https://repo1.maven.org/maven2/org/slf4j/

# Compile with correct classpath
javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/*.java
```

#### Issue: Cannot Connect to PostgreSQL

**Symptoms:**
```
org.postgresql.util.PSQLException: Connection refused
```

**Causes:**
- PostgreSQL not running
- Wrong host/port in config
- Firewall blocking connection
- PostgreSQL not configured to accept remote connections

**Solutions:**
```bash
# 1. Check PostgreSQL is running
systemctl status postgresql  # Linux
brew services list | grep postgres  # macOS

# 2. Verify connection manually
psql -U postgres -h 192.168.171.145 -d wazuhdb

# 3. Check PostgreSQL configuration
# Edit postgresql.conf:
listen_addresses = '*'

# Edit pg_hba.conf:
host    all    postgres    192.168.0.0/16    md5

# 4. Restart PostgreSQL
systemctl restart postgresql
```

#### Issue: Cannot Connect to RabbitMQ

**Symptoms:**
```
java.net.ConnectException: Connection refused
```

**Causes:**
- RabbitMQ not running
- Wrong host/port in config
- Authentication failure

**Solutions:**
```bash
# 1. Check RabbitMQ is running
systemctl status rabbitmq-server  # Linux
brew services list | grep rabbitmq  # macOS

# 2. Verify RabbitMQ management interface
curl http://192.168.86.76:15672

# 3. Check user credentials
rabbitmqctl list_users

# 4. Enable guest remote access (if needed)
# Edit /etc/rabbitmq/rabbitmq.conf:
loopback_users = none

# 5. Restart RabbitMQ
systemctl restart rabbitmq-server
```

#### Issue: Duplicate Alerts

**Symptoms:**
Database contains multiple entries with same `event_id`

**Cause:**
This should not happen due to duplicate detection logic

**Investigation:**
```sql
-- Find duplicates
SELECT event_id, COUNT(*) as count
FROM wazuh_alerts
GROUP BY event_id
HAVING COUNT(*) > 1;

-- Inspect specific duplicate
SELECT * FROM wazuh_alerts WHERE event_id = 'suspicious-uuid';
```

**Solution:**
```sql
-- Remove duplicates (keep oldest)
DELETE FROM wazuh_alerts a
USING wazuh_alerts b
WHERE a.log_id > b.log_id
  AND a.event_id = b.event_id;
```

#### Issue: Messages Stuck in Queue

**Symptoms:**
RabbitMQ queue has messages but listener not processing

**Causes:**
- Listener not running
- Listener crashed
- Database unreachable (causing NACK loop)

**Solutions:**
```bash
# 1. Check listener is running
ps aux | grep RabbitMQListener

# 2. Restart listener
java -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener

# 3. Check database connectivity
psql -U postgres -h 192.168.171.145 -d wazuhdb -c "SELECT 1;"

# 4. Inspect queue
curl -u guest:guest http://192.168.86.76:15672/api/queues/%2F/alerts_queue
```

#### Issue: Out of Memory

**Symptoms:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Causes:**
- Processing large JSON files
- Long-running process with memory leaks
- Insufficient heap size

**Solutions:**
```bash
# Increase heap size
java -Xmx2G -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener

# For very large datasets
java -Xmx4G -Xms1G -cp "out:lib/*" com.yourorg.middleware.AlertProcessor
```

#### Issue: JSON Parsing Errors

**Symptoms:**
```
org.json.JSONException: A JSONObject text must begin with '{'
```

**Causes:**
- Invalid JSON in message files
- Corrupted message in queue

**Solutions:**
```bash
# Validate JSON files
for file in messages/*.json; do
    echo "Checking $file"
    jq empty "$file" || echo "Invalid JSON: $file"
done

# Pretty-print and fix JSON
jq . messages/alert_1.json > messages/alert_1_fixed.json
```

#### Issue: Config Not Loading

**Symptoms:**
```
⚠ config.properties not found, using default values
```

**Causes:**
- File doesn't exist
- Wrong file location
- Running from wrong directory

**Solutions:**
```bash
# Check current directory
pwd  # Should be ThreatContextStore/

# Verify file exists
ls -la config.properties

# Create from template
cp config.properties.example config.properties

# Run from correct directory
cd /path/to/ThreatContextStore
java -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener
```

### Debugging Tips

**Enable Verbose Logging:**
```bash
# Create slf4j-simple.properties
echo "org.slf4j.simpleLogger.defaultLogLevel=debug" > slf4j-simple.properties

# Run with verbose logging
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
     -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener
```

**Test Individual Components:**
```bash
# Test database connection only
psql -U postgres -h 192.168.171.145 -d wazuhdb -c "SELECT NOW();"

# Test RabbitMQ connection only
telnet 192.168.86.76 5672

# Test JSON parsing
jq . messages/alert_1.json
```

**Monitor Resource Usage:**
```bash
# Watch Java processes
watch "ps aux | grep java | grep middleware"

# Monitor database connections
watch "psql -U postgres -h 192.168.171.145 -d wazuhdb -c \
  'SELECT count(*) FROM pg_stat_activity;'"
```

---

## Development

### Code Structure

```
src/main/java/com/yourorg/middleware/
├── ConfigLoader.java       # Configuration management
├── WazuhAlertDao.java      # Database operations
├── RabbitMQListener.java   # Production consumer
├── ListenerWorker.java     # Simple consumer
├── AlertProcessor.java     # Alert publisher
├── QueryDemo.java          # Query and export
└── DatabaseUtil.java       # Legacy (not used)
```

### Adding Custom Alert Fields

**Scenario:** Add a new field to the JSONB payload

1. **No schema changes needed** (JSONB is flexible)

2. **Query the new field:**
```sql
SELECT payload->>'new_field' FROM wazuh_alerts;
```

3. **Add to query methods:**
```java
// In WazuhAlertDao.java
public List<JSONObject> queryByCustomField(String fieldValue) {
    String sql = "SELECT * FROM wazuh_alerts WHERE payload->>'new_field' = ?";
    // ... implementation
}
```

### Extending Functionality

**Adding a New Consumer:**

1. Create new class extending similar pattern:
```java
public class CustomListener {
    public static void main(String[] args) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(ConfigLoader.getRabbitMqHost());
        // ... implement custom logic
    }
}
```

2. Compile:
```bash
javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/CustomListener.java
```

3. Run:
```bash
java -cp "out:lib/*" com.yourorg.middleware.CustomListener
```

### Testing

**Unit Testing Database Operations:**
```java
// Example test
public static void testInsertAlert() {
    WazuhAlertDao dao = new WazuhAlertDao();
    JSONObject testAlert = new JSONObject();
    testAlert.put("event_id", UUID.randomUUID().toString());
    testAlert.put("timestamp", Instant.now().toString());
    testAlert.put("event_type", "test");
    testAlert.put("source_module", "test");
    testAlert.put("payload", new JSONObject().put("severity", "test"));
    
    try {
        dao.insertAlert(testAlert);
        System.out.println("✅ Test passed");
    } catch (Exception e) {
        System.err.println("❌ Test failed: " + e.getMessage());
    }
}
```

**Integration Testing:**
1. Start RabbitMQListener
2. Run AlertProcessor with test data
3. Query database to verify
4. Export with QueryDemo
5. Verify output files

### Best Practices

**Configuration Management:**
- Always use ConfigLoader, never hardcode values
- Keep config.properties out of version control
- Document any new configuration options in config.properties.example

**Error Handling:**
- Log errors with context (event_id, timestamp, etc.)
- Use try-with-resources for connections
- NACK messages on error in production listeners

**Database Operations:**
- Always use PreparedStatement to prevent SQL injection
- Check for duplicates before inserting
- Use connection pooling for high-volume scenarios

**Message Processing:**
- Use manual ACK in production (RabbitMQListener pattern)
- Set appropriate prefetch count (1 for heavy processing)
- Implement graceful shutdown hooks

---

## Appendix

### Alert JSON Schema

**Standard Alert Structure:**
```json
{
  "event_id": "uuid-string",
  "timestamp": "2025-07-21T10:36:12Z",
  "event_type": "alerts.host.wazuh",
  "source_module": "WazuhConnector",
  "payload": {
    "host_id": "host-192.168.1.101",
    "alert_type": "ransomware_detection",
    "signature_id": "9201001",
    "signature": "Suspicious file encryption activity",
    "severity": "high",
    "process": "C:\\Users\\User1\\AppData\\Local\\Temp\\malware.exe",
    "file_path": "C:\\Users\\User1\\Documents\\file1.docx",
    "source_ip": "192.168.1.101",
    "destination_ip": "10.0.0.1",
    "protocol": "TCP",
    "threat_score": 22
  }
}
```

### Useful SQL Queries

**Find alerts by time range:**
```sql
SELECT * FROM wazuh_alerts 
WHERE timestamp BETWEEN '2025-07-01' AND '2025-07-31'
ORDER BY timestamp DESC;
```

**Top 10 most common alert types:**
```sql
SELECT 
    payload->>'alert_type' AS alert_type,
    COUNT(*) as count
FROM wazuh_alerts
GROUP BY payload->>'alert_type'
ORDER BY count DESC
LIMIT 10;
```

**Alerts with high threat scores:**
```sql
SELECT 
    event_id,
    timestamp,
    payload->>'alert_type',
    (payload->>'threat_score')::int AS score
FROM wazuh_alerts
WHERE (payload->>'threat_score')::int > 20
ORDER BY (payload->>'threat_score')::int DESC;
```

### Performance Tuning

**PostgreSQL Indexes:**
```sql
-- Index on timestamp for time-range queries
CREATE INDEX idx_wazuh_alerts_timestamp ON wazuh_alerts(timestamp);

-- Index on event_id for duplicate checks
CREATE INDEX idx_wazuh_alerts_event_id ON wazuh_alerts(event_id);

-- GIN index on JSONB for faster payload queries
CREATE INDEX idx_wazuh_alerts_payload ON wazuh_alerts USING GIN(payload);
```

**RabbitMQ Tuning:**
- Set prefetch count based on processing time
- Use durable queues for persistence
- Monitor queue depth and adjust consumers

---

## Support

For issues or questions:
1. Check this guide's [Troubleshooting](#troubleshooting) section
2. Review logs for error messages
3. Verify infrastructure (PostgreSQL, RabbitMQ) is healthy
4. Check configuration in `config.properties`

---

**Document Version:** 1.0  
**Last Updated:** 2025-10-07  
**Compatible With:** ThreatContextStore v1.0


---

## Message Type System (NEW)

### Overview

ThreatContextStore now supports a message-type based routing system with three distinct message types: `alert`, `query`, and `query_response`. This enables dynamic querying capabilities and real-time response distribution.

### Message Types

#### 1. Alert Messages (`message_type: "alert"`)

**Purpose:** Security alerts from Wazuh or other sources

**Processing:**
- Stored in database for analysis
- Can be queried later using query messages
- Duplicate detection based on `event_id`

**Example Structure:**
```json
{
  "message_type": "alert",
  "event_id": "4e5344ac-7df0-4cc2-9714-2af6ba41a983",
  "timestamp": "2025-10-07T01:00:00Z",
  "event_type": "alerts.host.wazuh",
  "source_module": "WazuhConnector",
  "payload": {
    "severity": "high",
    "alert_type": "ransomware_detection",
    "host_id": "host-192.168.1.101",
    "signature_id": "9201001",
    "signature": "Suspicious file encryption activity",
    "process": "C:\\Users\\User1\\AppData\\Local\\Temp\\malware.exe",
    "file_path": "C:\\Users\\User1\\Documents\\file1.docx",
    "source_ip": "192.168.1.101",
    "destination_ip": "10.0.0.1",
    "protocol": "TCP",
    "threat_score": 22
  }
}
```

#### 2. Query Messages (`message_type: "query"`)

**Purpose:** Request to query the database dynamically

**Processing:**
1. Stored in database with execution status (`pending`)
2. JSON query translated to SQL using QueryTranslator
3. SQL query executed against database
4. Each result sent as individual `query_response` message
5. Query status updated (`success` or `failed`) with response count

**Example Structure:**
```json
{
  "message_type": "query",
  "event_id": "query-12345",
  "timestamp": "2025-10-07T01:00:00Z",
  "event_type": "query.request",
  "source_module": "AdminConsole",
  "payload": {
    "filters": {
      "severity": "high",
      "alert_type": "ransomware_detection"
    },
    "order_by": "timestamp",
    "order_direction": "DESC",
    "limit": 100
  }
}
```

**Query Payload Fields:**
- `filters` (object): Field/value pairs for WHERE clause (queries JSONB payload)
- `order_by` (string): Field to sort by (default: `timestamp`)
- `order_direction` (string): `DESC` or `ASC` (default: `DESC`)
- `limit` (integer): Maximum number of results (default: 100)

#### 3. Query Response Messages (`message_type: "query_response"`)

**Purpose:** Individual results from a query execution

**Processing:**
- **NOT stored** in database (only logged)
- Sent to `query_response_queue` on RabbitMQ
- Written to `query_responses/{event_id}.json` files

**Example Structure:**
```json
{
  "message_type": "query_response",
  "event_id": "4e5344ac-7df0-4cc2-9714-2af6ba41a983",
  "timestamp": "2025-07-21T10:36:12Z",
  "event_type": "alerts.host.wazuh",
  "source_module": "WazuhConnector",
  "payload": {
    "severity": "high",
    "alert_type": "ransomware_detection",
    "host_id": "host-192.168.1.101",
    ...
  }
}
```

### Updated Architecture

#### Dual-Queue System

```
                      ┌──────────────────┐
                      │  alerts_queue    │  ← Receives alerts & queries
                      └────────┬─────────┘
                               │
                               v
                      ┌──────────────────┐
                      │ RabbitMQListener │
                      └────────┬─────────┘
                               │
           ┌───────────────────┼───────────────────┐
           │                   │                   │
           v                   v                   v
     message_type:       message_type:       message_type:
        "alert"             "query"          "query_response"
           │                   │                   │
           v                   v                   v
      Store in DB        Execute Query         Log only
                               │              (not stored)
                               │
                               v
                    Generate query_response
                         messages
                               │
                   ┌───────────┴───────────┐
                   │                       │
                   v                       v
      ┌──────────────────────┐   ┌─────────────────┐
      │ query_response_queue │   │ query_responses/│
      │     (RabbitMQ)       │   │   {event_id}.json│
      └──────────────────────┘   └─────────────────┘
```

### Configuration Updates

**New Configuration Option:**
```properties
# Query response queue name
rabbitmq.query_response_queue.name=query_response_queue

# Updated output path
paths.query_responses=query_responses
```

### Updated Database Schema

```sql
CREATE TABLE wazuh_alerts (
    event_id UUID PRIMARY KEY,              -- Unique event identifier
    message_type VARCHAR(50) NOT NULL,      -- alert, query, query_response
    timestamp TIMESTAMPTZ NOT NULL,         -- Event timestamp
    event_type VARCHAR(255) NOT NULL,       -- Event classification
    source_module VARCHAR(255) NOT NULL,    -- Source system identifier
    payload JSONB NOT NULL,                 -- Flexible JSON data
    response_count INTEGER DEFAULT 0,       -- For queries: number of responses sent
    response_status VARCHAR(50) DEFAULT 'pending'  -- For queries: success, failed, pending
);

-- Indexes
CREATE INDEX idx_wazuh_alerts_message_type ON wazuh_alerts(message_type);
CREATE INDEX idx_wazuh_alerts_timestamp ON wazuh_alerts(timestamp);
CREATE INDEX idx_wazuh_alerts_payload ON wazuh_alerts USING GIN(payload);
```

### New Component: QueryTranslator

**Purpose:** Translates JSON query messages into SQL queries

**How It Works:**
1. Extracts `filters` from query payload
2. Builds WHERE clause using JSONB operators (`payload->>'field'`)
3. Adds ORDER BY and LIMIT clauses
4. Returns SQL string with parameterized values

**Example Translation:**
```json
{
  "filters": {"severity": "high"},
  "order_by": "timestamp",
  "limit": 10
}
```

Translates to:
```sql
SELECT * FROM wazuh_alerts 
WHERE payload->>'severity' = ? 
ORDER BY timestamp DESC 
LIMIT 10
```

### Message Processing Flow

#### Alert Processing
```
1. Alert received on alerts_queue
2. RabbitMQListener routes to handleAlert()
3. Store in database (check for duplicates)
4. ACK message
```

#### Query Processing
```
1. Query received on alerts_queue
2. RabbitMQListener routes to handleQuery()
3. Store query in database (status: pending)
4. Translate JSON to SQL using QueryTranslator
5. Execute SQL query
6. For EACH result:
   a. Change message_type to "query_response"
   b. Send to query_response_queue
   c. Write to query_responses/{event_id}.json
7. Update query status (success/failed, response_count)
8. ACK message
```

#### Query Response Processing
```
1. Query response received on alerts_queue
2. RabbitMQListener routes to handleQueryResponse()
3. Log the message (NOT stored in database)
4. ACK message
```

### Example: End-to-End Query Flow

**Step 1: Send Query**
Create `messages/query_example.json`:
```json
{
  "message_type": "query",
  "event_id": "query-001",
  "timestamp": "2025-10-07T02:00:00Z",
  "event_type": "query.request",
  "source_module": "AdminConsole",
  "payload": {
    "filters": {
      "severity": "high"
    },
    "order_by": "timestamp",
    "order_direction": "DESC",
    "limit": 5
  }
}
```

**Step 2: Process Query**
```bash
java -cp "out:lib/*" com.yourorg.middleware.AlertProcessor
```

**Step 3: RabbitMQListener Output**
```
🔍 Processing query: query-001
   SQL: SELECT * FROM wazuh_alerts WHERE payload->>'severity' = ? ORDER BY timestamp DESC LIMIT 5
   Found 5 results
✅ Sent 5 query responses
```

**Step 4: Results**
- 5 messages sent to `query_response_queue`
- 5 files created in `query_responses/` directory
- Query record in database updated:
  ```sql
  event_id: query-001
  message_type: query
  response_count: 5
  response_status: success
  ```

### Backward Compatibility

All existing functionality is maintained:
- Old alert files without `message_type` default to "alert"
- AlertProcessor adds `message_type` if missing
- QueryDemo still works for exporting by severity
- ListenerWorker handles all message types

### Testing Message Types

```bash
# 1. Recompile with new changes
cd ThreatContextStore
javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/*.java

# 2. Recreate database with new schema
psql -U postgres -h 192.168.171.145 -f schema.sql

# 3. Start listener
java -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener

# 4. Send alerts (in another terminal)
java -cp "out:lib/*" com.yourorg.middleware.AlertProcessor

# 5. Verify in database
psql -U postgres -h 192.168.171.145 -d wazuhdb -c "SELECT message_type, COUNT(*) FROM wazuh_alerts GROUP BY message_type;"
```

### Troubleshooting Message Types

**Issue: Query not executing**
- Check that query message has proper structure
- Verify `filters` object exists in payload
- Check RabbitMQListener logs for SQL errors

**Issue: No query responses generated**
- Verify alerts exist matching the query filters
- Check response_status in database: `SELECT event_id, response_count, response_status FROM wazuh_alerts WHERE message_type = 'query';`

**Issue: Query responses not in queue**
- Verify `query_response_queue` is declared in RabbitMQ
- Check RabbitMQ management console for queue messages

---

**Document Version:** 2.0  
**Last Updated:** 2025-10-07  
**Compatible With:** ThreatContextStore v2.0 (with message_type support)
