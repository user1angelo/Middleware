# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Overview

This is a Java-based middleware system that bridges RabbitMQ message queues with PostgreSQL for security alert processing. It provides a message-type based routing system that handles alerts, queries, and query responses.

**Key Features:**
- **Three message types**: `alert`, `query`, `query_response`
- **Dynamic query translation**: JSON queries converted to SQL
- **Dual-queue system**: Separate queues for alerts and query responses
- **UUID-based event tracking**: Proper UUID support for event_id
- **Query execution tracking**: Stores query status and response counts

**Project Structure:**
```
Middleware/
├── WARP.md
└── ThreatContextStore/          # Main application directory
    ├── src/main/java/com/yourorg/middleware/
    ├── lib/                      # JAR dependencies
    ├── messages/                 # Input JSON message files
    ├── query_responses/          # Query response output files
    ├── out/                      # Compiled class files
    ├── config.properties.example # Configuration template
    ├── config.properties         # Your local config (git-ignored)
    └── schema.sql                # Database schema
```

## Configuration

### Initial Setup

1. **Navigate to the project directory:**
   ```bash
   cd ThreatContextStore
   ```

2. **Create your configuration file:**
   ```bash
   cp config.properties.example config.properties
   ```

3. **Edit `config.properties`** to match your environment:
   - Database host, port, credentials
   - RabbitMQ host, port, credentials
   - File paths (if different from defaults)

**Note:** The application will use default values if `config.properties` is not found, but creating one is recommended for clarity.

### Configuration Options

**Database Configuration:**
- `db.host` - PostgreSQL server hostname (default: `localhost`)
- `db.port` - PostgreSQL port (default: `5432`)
- `db.name` - Database name (default: `wazuhdb`)
- `db.user` - Database username (default: `postgres`)
- `db.password` - Database password (default: `postgres`)

**RabbitMQ Configuration:**
- `rabbitmq.host` - RabbitMQ server hostname (default: `localhost`)
- `rabbitmq.port` - RabbitMQ port (default: `5672`)
- `rabbitmq.user` - RabbitMQ username (default: `user`)
- `rabbitmq.password` - RabbitMQ password (default: `password`)
- `rabbitmq.queue.name` - Main queue for alerts/queries (default: `alerts_queue`)
- `rabbitmq.query_response_queue.name` - Queue for query responses (default: `query_response_queue`)

**Note:** Some repo docs may contain IP addresses from a specific lab setup. Treat those as examples and prefer the values in your local `ThreatContextStore/config.properties`.

**File Paths:**
- `paths.messages` - Directory for input JSON files (default: `messages`)
- `paths.query_responses` - Directory for query response output (default: `query_responses`)

## Build and Run Commands

### Prerequisites
- Java 17+ must be installed
- PostgreSQL 17 must be running (default port 5432)
- RabbitMQ server must be accessible (typically at port 5672)
- All required JAR files must be in the `lib/` directory:
  - `postgresql-42.7.7.jar` (JDBC driver)
  - `amqp-client-5.26.0.jar` (RabbitMQ client)
  - `json-20231013.jar` (JSON processing)
  - `slf4j-api-2.0.17.jar` and `slf4j-simple-2.0.17.jar` (logging)

### Compile the Project

**Important:** Always run commands from the `ThreatContextStore/` directory.

```bash
cd ThreatContextStore
```

**On Linux/macOS:**
```bash
javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/*.java
```

**On Windows:**
```bash
javac -cp "lib/*;out" -d out src/main/java/com/yourorg/middleware/*.java
```

### Run ThreatContextStore

**Primary Method - Run Everything (Recommended):**

This starts both RabbitMQListener and file-watching AlertProcessor as separate threads in one process:

```bash
# Linux/macOS
java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain

# Windows
java -cp "out;lib/*" com.yourorg.middleware.ThreatContextStoreMain
```

**Features:**
- Runs RabbitMQ Listener and Alert Processor in separate threads
- Automatically processes existing files in `messages/` at startup
- Watches `messages/` directory for new files and auto-processes them
- Graceful shutdown with CTRL+C
- Single process, easier to manage

### Run Individual Components (Alternative)

You can still run components separately for testing or specific use cases:

**RabbitMQ Listener** (consumes from queue and stores in PostgreSQL):
```bash
# Linux/macOS
java -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener

# Windows
java -cp "out;lib/*" com.yourorg.middleware.RabbitMQListener
```

**Alert Processor** (sends JSON files from messages/ to RabbitMQ - one-time):
```bash
# Linux/macOS
java -cp "out:lib/*" com.yourorg.middleware.AlertProcessor

# Windows
java -cp "out;lib/*" com.yourorg.middleware.AlertProcessor
```

**Query Demo** (export alerts by severity):
```bash
# Linux/macOS
java -cp "out:lib/*" com.yourorg.middleware.QueryDemo high

# Windows
java -cp "out;lib/*" com.yourorg.middleware.QueryDemo high
```

Replace `high` with desired severity level (e.g., `medium`, `low`, `critical`).

**ListenerWorker** (alternative listener implementation):
```bash
# Linux/macOS
java -cp "out:lib/*" com.yourorg.middleware.ListenerWorker

# Windows
java -cp "out;lib/*" com.yourorg.middleware.ListenerWorker
```

### Database Setup

Initialize the PostgreSQL database from the `ThreatContextStore/` directory:
```bash
psql -U postgres -f schema.sql
```

Or manually create the database:
```sql
CREATE DATABASE wazuhdb;
CREATE USER postgres WITH PASSWORD 'postgres';
GRANT ALL PRIVILEGES ON DATABASE wazuhdb TO postgres;
\c wazuhdb
-- Run schema.sql contents
```

## Architecture

### Core Components

**Message Flow Pipeline:**
```
Message Files (JSON) → AlertProcessor → alerts_queue (RabbitMQ)
                                              ↓
                                    RabbitMQListener
                                    (routes by message_type)
                                              ↓
                        ┌─────────────┬───────────────┐
              alert │         query │   query_response │
                        │               │                 │
                        v               v                 v
                 Store in DB   Execute Query      Log only
                                      │           (not stored)
                                      v
                            Generate responses
                                      │
                        ┌─────────────┴───────────────┐
                        │                               │
                        v                               v
          query_response_queue            query_responses/
               (RabbitMQ)                  (JSON files)
```

### Key Classes

**1. ThreatContextStoreMain** (`ThreatContextStoreMain.java`)
- Main application entry point (recommended for production)
- Runs RabbitMQListener and AlertProcessor as separate threads
- File watcher monitors `messages/` directory for new files
- Automatically processes files as they appear
- Single process with graceful shutdown
- Uses thread pool for concurrent operations

**2. ConfigLoader** (`ConfigLoader.java`)
- Central configuration management
- Reads from `config.properties` with fallback to defaults
- Provides type-safe getters for all configuration values
- Used by all components for consistent configuration

**3. WazuhAlertDao** (`WazuhAlertDao.java`)
- Data Access Object for PostgreSQL operations
- Handles message insertion with duplicate detection (checks `event_id`)
- Stores alerts and queries (not query_responses)
- Provides query methods to fetch alerts by severity from JSONB payload
- Executes dynamic SQL queries from translated JSON
- Tracks query execution status (response_count, response_status)
- Uses ConfigLoader for database connection settings

**4. QueryTranslator** (`QueryTranslator.java`)
- Translates JSON query messages into SQL queries
- Builds WHERE clauses from JSON filters
- Supports ordering, limiting, and JSONB field queries
- Returns SQL string and parameterized values

**5. RabbitMQListener** (`RabbitMQListener.java`)
- Primary consumer implementation with robust error handling
- Routes messages based on `message_type` field:
  - **alert**: Store in database
  - **query**: Execute query, generate responses, send to `query_response_queue`
  - **query_response**: Log only (not stored)
- Uses manual acknowledgment (ACK/NACK) for reliable message processing
- Includes graceful shutdown hooks
- Uses ConfigLoader for RabbitMQ connection settings

**6. ListenerWorker** (`ListenerWorker.java`)
- Alternative, simpler listener implementation
- Uses automatic acknowledgment mode
- Handles message_type routing (alerts and queries only)
- Suitable for less critical processing scenarios
- Uses ConfigLoader for RabbitMQ connection settings

**7. AlertProcessor** (`AlertProcessor.java`)
- Reads JSON files from `messages/` directory (configurable via config.properties)
- Publishes messages to RabbitMQ queue (one-time run)
- Ensures all messages have `message_type` field (defaults to "alert")
- Uses relative paths for cross-platform compatibility
- Uses ConfigLoader for all settings

**8. QueryDemo** (`QueryDemo.java`)
- Queries PostgreSQL for alerts matching a severity level
- Exports results to `query_responses/` directory as individual JSON files
- Adds `message_type: query_response` to exported files
- Optionally republishes alerts back to RabbitMQ
- Requires severity parameter as command-line argument
- Uses ConfigLoader for all settings

**9. DatabaseUtil** (`DatabaseUtil.java`)
- Legacy database connection utility (not currently used)
- Kept for documentation and potential future use

### Database Schema

The `wazuh_alerts` table stores:
- `event_id` (UUID, PRIMARY KEY): Unique event identifier
- `message_type` (VARCHAR, NOT NULL): Message type (`alert`, `query`, `query_response`)
- `timestamp` (TIMESTAMPTZ, NOT NULL): Event timestamp
- `event_type` (VARCHAR, NOT NULL): Event classification
- `source_module` (VARCHAR, NOT NULL): Source system identifier
- `payload` (JSONB, NOT NULL): Full alert data stored as JSON for flexible querying
- `response_count` (INTEGER, DEFAULT 0): For queries - number of responses sent
- `response_status` (VARCHAR, DEFAULT 'pending'): For queries - `pending`, `success`, `failed`

**Indexes:**
- `idx_wazuh_alerts_message_type` - For filtering by message type
- `idx_wazuh_alerts_timestamp` - For time-range queries
- `idx_wazuh_alerts_payload` (GIN) - For fast JSONB queries

### Message Types

**1. Alert Messages** (`message_type: "alert"`)
- Security alerts from Wazuh or other sources
- Stored in database for analysis
- Example structure:
```json
{
  "message_type": "alert",
  "event_id": "uuid",
  "timestamp": "2025-10-07T01:00:00Z",
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

**2. Query Messages** (`message_type: "query"`)
- Request to query the database
- Stored in database with execution status
- Triggers SQL query execution
- Results sent as individual `query_response` messages
- Example structure:
```json
{
  "message_type": "query",
  "event_id": "query-uuid",
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

**3. Query Response Messages** (`message_type: "query_response"`)
- Individual results from a query
- Sent to `query_response_queue` on RabbitMQ
- Written to `query_responses/{event_id}.json` files
- **NOT** stored in database (logged only)
- Example structure:
```json
{
  "message_type": "query_response",
  "event_id": "original-alert-uuid",
  "timestamp": "2025-07-21T10:36:12Z",
  "event_type": "alerts.host.wazuh",
  "source_module": "WazuhConnector",
  "payload": {
    "severity": "high",
    ...
  }
}
```

### Configuration Management

All configuration is now centralized through `ConfigLoader.java` and `config.properties`:

- **Single source of truth**: All components use ConfigLoader for settings
- **No hardcoded values**: All connection strings and paths are configurable
- **Cross-platform**: Uses relative paths that work on any OS
- **Fallback defaults**: Application works even without config.properties file

**Note:** `DatabaseUtil.java` is a legacy file with different configuration and is not actively used by the application.

## Development Workflow

### Working with Messages

**All messages must include:**
- `message_type`: `"alert"`, `"query"`, or `"query_response"`
- `event_id`: UUID string
- `timestamp`: ISO 8601 format
- `event_type`: Event classification
- `source_module`: Source identifier
- `payload`: JSONB object with message-specific data

**Alert Payload Structure** (flexible, commonly includes):
- `severity`: Used for filtering queries
- `host_id`, `alert_type`, `signature_id`, `signature`
- Network details: `source_ip`, `destination_ip`, `protocol`
- File/process information as needed

**Query Payload Structure**:
- `filters`: Object with field/value pairs to query
- `order_by`: Field to sort by (default: `timestamp`)
- `order_direction`: `DESC` or `ASC` (default: `DESC`)
- `limit`: Maximum results (default: 100)

### Querying JSONB Fields
PostgreSQL JSONB queries use the `->>` operator for text extraction:
```sql
SELECT * FROM wazuh_alerts WHERE payload->>'severity' = 'high';
SELECT * FROM wazuh_alerts WHERE payload->>'alert_type' = 'ransomware_detection';
```

### Testing the Pipeline

**All commands assume you're in the `ThreatContextStore/` directory.**

1. **Start RabbitMQ server** (on 192.168.86.76 or configure in config.properties)
2. **Start PostgreSQL database** (on 192.168.86.28 or configure in config.properties)
3. **Ensure config.properties exists:**
   ```bash
   cp config.properties.example config.properties
   # Edit config.properties if needed
   ```
4. **Run ThreatContextStoreMain** (starts everything):
   ```bash
   java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain
   ```
   This will:
   - Process all existing files in `messages/`
   - Start listening for RabbitMQ messages
   - Watch for new files in `messages/` directory

5. **Add new alert files** (in another terminal while ThreatContextStoreMain is running):
   ```bash
   # Copy a test file
   cp messages/alert_1.json messages/test_alert.json
   # It will be automatically processed!
   ```

6. **Verify insertion** with QueryDemo or direct SQL queries:
   ```bash
   java -cp "out:lib/*" com.yourorg.middleware.QueryDemo high
   ```

**Alternative: Run Components Separately**

If you need to run components individually:
```bash
# Terminal 1: Start listener
java -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener

# Terminal 2: Process files once
java -cp "out:lib/*" com.yourorg.middleware.AlertProcessor
```

### Message Processing Guarantees
- `RabbitMQListener` uses manual ACK for at-least-once delivery
- `ListenerWorker` uses auto-ACK for at-most-once delivery
- Duplicate alerts are prevented by checking `event_id` before insertion
- Failed messages in `RabbitMQListener` are requeued with NACK

