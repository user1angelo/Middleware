# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Overview

This is a Java-based middleware system that bridges RabbitMQ message queues with PostgreSQL for security alert processing. It receives Wazuh security alerts from RabbitMQ, processes them, and stores them in a PostgreSQL database for querying and analysis.

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
```bash
javac -cp "lib/*" -d out src/main/java/com/yourorg/middleware/*.java
```

**On Linux/macOS**, use colon separator:
```bash
javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/*.java
```

**On Windows**, use semicolon separator:
```bash
javac -cp "lib/*;out" -d out src/main/java/com/yourorg/middleware/*.java
```

### Run Individual Components

**RabbitMQ Listener** (consumes from queue and stores in PostgreSQL):
```bash
# Linux/macOS
java -cp "out:lib/*" com.yourorg.middleware.RabbitMQListener

# Windows
java -cp "out;lib/*" com.yourorg.middleware.RabbitMQListener
```

**Alert Processor** (sends JSON files from messages/ to RabbitMQ):
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

Initialize the PostgreSQL database:
```bash
psql -U postgres -f middlewaresender-latest/middlewaresender-main/schema.sql
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
Alert Files (JSON) → AlertProcessor → RabbitMQ Queue → RabbitMQListener/ListenerWorker → PostgreSQL
                                            ↓
                                      QueryDemo ← PostgreSQL
```

### Key Classes

**1. WazuhAlertDao** (`WazuhAlertDao.java`)
- Data Access Object for PostgreSQL operations
- Handles alert insertion with duplicate detection (checks `event_id`)
- Provides query methods to fetch alerts by severity from JSONB payload
- Database connection: `192.168.86.28:5432/wazuhdb`

**2. RabbitMQListener** (`RabbitMQListener.java`)
- Primary consumer implementation with robust error handling
- Connects to RabbitMQ at `192.168.86.76:5672`
- Uses manual acknowledgment (ACK/NACK) for reliable message processing
- Flattens nested `payload` objects before database insertion
- Includes graceful shutdown hooks

**3. ListenerWorker** (`ListenerWorker.java`)
- Alternative, simpler listener implementation
- Uses automatic acknowledgment mode
- Suitable for less critical processing scenarios

**4. AlertProcessor** (`AlertProcessor.java`)
- Reads JSON files from `messages/` directory
- Publishes each alert to RabbitMQ queue `alerts_queue`
- Currently hardcoded to Windows path: `D:\Users\Angelo\Downloads\middlewaresender-latest\middlewaresender-main\messages`
- **Note**: Update this path when running on different systems

**5. QueryDemo** (`QueryDemo.java`)
- Queries PostgreSQL for alerts matching a severity level
- Exports results to `output/` directory as individual JSON files
- Optionally republishes alerts back to RabbitMQ
- Requires severity parameter as command-line argument

**6. DatabaseUtil** (`DatabaseUtil.java`)
- Provides database connection pooling utility
- Configured for local PostgreSQL: `localhost:5432/alertsdb`
- **Note**: This class uses different database credentials than `WazuhAlertDao`

### Database Schema

The `wazuh_alerts` table stores:
- `log_id` (TEXT, PRIMARY KEY): Composite key with timestamp, event type, and UUID
- `event_id` (TEXT, NOT NULL): Original event UUID from JSON
- `timestamp` (TIMESTAMPTZ): Alert timestamp
- `event_type` (TEXT): Event classification
- `source_module` (TEXT): Source system identifier
- `payload` (JSONB): Full alert data stored as JSON for flexible querying

### Configuration Differences

**Important**: The codebase has inconsistent configuration across components:

1. **Database Connections:**
   - `WazuhAlertDao`: Uses `192.168.86.28:5432/wazuhdb` with `postgres/postgres`
   - `DatabaseUtil`: Uses `localhost:5432/alertsdb` with `alerts_user/alertspass`

2. **RabbitMQ Connections:**
   - All components use `192.168.86.76:5672` with `guest/guest` credentials
   - Queue name: `alerts_queue` (consistent across all components)

3. **File Paths:**
   - `AlertProcessor` has hardcoded Windows path that needs updating for cross-platform use

When working with this codebase, ensure these configurations match your environment.

## Development Workflow

### Adding New Alert Types
1. Add sample JSON to `messages/` directory following the schema pattern:
   - `event_id`: UUID
   - `timestamp`: ISO 8601 format
   - `event_type`: Alert classification
   - `source_module`: Source identifier
   - `payload`: JSONB object with alert details (including `severity` field)

2. The payload structure is flexible but commonly includes:
   - `severity`: Used for filtering queries
   - `host_id`, `alert_type`, `signature_id`, `signature`
   - Network details: `source_ip`, `destination_ip`, `protocol`
   - File/process information as needed

### Querying JSONB Fields
PostgreSQL JSONB queries use the `->>` operator for text extraction:
```sql
SELECT * FROM wazuh_alerts WHERE payload->>'severity' = 'high';
SELECT * FROM wazuh_alerts WHERE payload->>'alert_type' = 'ransomware_detection';
```

### Testing the Pipeline
1. Start RabbitMQ server
2. Start PostgreSQL database
3. Run `RabbitMQListener` in one terminal
4. Run `AlertProcessor` to send test alerts
5. Verify insertion with `QueryDemo` or direct SQL queries

### Message Processing Guarantees
- `RabbitMQListener` uses manual ACK for at-least-once delivery
- `ListenerWorker` uses auto-ACK for at-most-once delivery
- Duplicate alerts are prevented by checking `event_id` before insertion
- Failed messages in `RabbitMQListener` are requeued with NACK

