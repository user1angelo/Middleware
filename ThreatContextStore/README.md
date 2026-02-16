# ThreatContextStore

ThreatContextStore ingests messages from RabbitMQ and stores alert/query data in PostgreSQL.

## What it does

- Consumes `alert` and `query` messages from `rabbitmq.queue.name` (default: `alerts_queue`)
- Stores alerts/queries in PostgreSQL (table: `wazuh_alerts`)
- For `query` messages, executes the translated SQL and:
  - publishes individual results to `rabbitmq.query_response_queue.name` (default: `query_response_queue`)
  - writes result files to `query_responses/`
- `query_response` messages are logged (not stored)

## Prerequisites

- Java 17+
- PostgreSQL (14+ recommended)
- RabbitMQ (3.9+ recommended)
- JAR dependencies already included under `lib/`

## Configure

Create a local config:

```bash
cp config.properties.example config.properties
```

Edit `config.properties` to match your environment. If `config.properties` is missing, defaults in `src/main/java/com/yourorg/middleware/ConfigLoader.java` are used.

## Database setup

Create the schema:

```bash
psql -h <DB_HOST> -U <DB_USER> -d <DB_NAME> -f schema.sql
```

If needed, apply the migration for query tracking columns:

```bash
psql -h <DB_HOST> -U <DB_USER> -d <DB_NAME> -f migration_add_query_columns.sql
```

## Build (javac)

From this directory:

```bash
# Windows
javac -cp "lib/*;out" -d out src/main/java/com/yourorg/middleware/*.java

# Linux/macOS
# javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/*.java
```

## Run

Start the main entrypoint (listener + file watcher):

```bash
# Windows
java -cp "out;lib/*" com.yourorg.middleware.ThreatContextStoreMain

# Linux/macOS
# java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain
```

## Test

Use the tester in `../ThreatContextStoreTester/` to publish synthetic alerts and queries.

Query response files will appear in `query_responses/`.
