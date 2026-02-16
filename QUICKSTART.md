# Middleware Quick Start (ThreatContextStore)

This quick start is intentionally **environment-agnostic**.

Instead of hard-coding IPs/ports, it uses the repo’s configuration keys:
- ThreatContextStore reads `ThreatContextStore/config.properties` (or defaults defined in `ConfigLoader.java`).
- Queue names and DB connection details are **configurable**.

## Prerequisites

- Java 17+
- PostgreSQL (14+ recommended)
- RabbitMQ (3.9+ recommended)

## 1) Configure

From the repo root:

```bash
cd ThreatContextStore
cp config.properties.example config.properties
```

Edit `ThreatContextStore/config.properties` and set at minimum:
- `db.host`, `db.port`, `db.name`, `db.user`, `db.password`
- `rabbitmq.host`, `rabbitmq.port`, `rabbitmq.user`, `rabbitmq.password`
- `rabbitmq.queue.name` (default: `alerts_queue`)
- `rabbitmq.query_response_queue.name` (default: `query_response_queue`)

## 2) Ensure DB schema is up-to-date

Run the base schema once:

```bash
cd ThreatContextStore
psql -h <DB_HOST> -U <DB_USER> -d <DB_NAME> -f schema.sql
```

If you see errors about missing `response_count` / `response_status`, run the migration:

```bash
cd ThreatContextStore
psql -h <DB_HOST> -U <DB_USER> -d <DB_NAME> -f migration_add_query_columns.sql
```

## 3) Compile

ThreatContextStore is `javac`-based.

```bash
cd ThreatContextStore

# Windows (PowerShell/cmd)
javac -cp "lib/*;out" -d out src/main/java/com/yourorg/middleware/*.java

# Linux/macOS
# javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/*.java
```

## 4) Run

Start ThreatContextStore (runs the RabbitMQ listener + file-watcher sender in one process):

```bash
cd ThreatContextStore

# Windows
java -cp "out;lib/*" com.yourorg.middleware.ThreatContextStoreMain

# Linux/macOS
# java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain
```

You should see it connect using your configured values and begin waiting for messages from `rabbitmq.queue.name`.

## 5) Test (optional)

If you want to generate synthetic alerts/queries, use the tester:

```bash
cd ThreatContextStoreTester

# Windows
java -cp ".;../ThreatContextStore/lib/*" TCSTester

# Linux/macOS
# java -cp ".:../ThreatContextStore/lib/*" TCSTester
```

Query responses are written by ThreatContextStore to `ThreatContextStore/query_responses/`.

## Stopping

Press **CTRL+C** in the ThreatContextStore terminal; it performs a graceful shutdown.

