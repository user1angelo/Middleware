# ThreatContextStore Tester (TCSTester)

Interactive testing tool for ThreatContextStore that sends random alerts and queries via RabbitMQ.

## Features

- **Send Random Alerts**: Generate and send realistic random security alerts
- **Interactive Queries**: Build custom queries with multiple filters
- **Three Send Modes**:
  1. Send specific count
  2. Send fixed 10 alerts
  3. Keep sending until interrupted

## Prerequisites

- Java 17+
- RabbitMQ reachable at the host/port configured for your environment
- JAR files from ThreatContextStore `/lib` directory

**Note:** ThreatContextStore’s RabbitMQ connection settings are read from `ThreatContextStore/config.properties` (or defaults in `ThreatContextStore/src/main/java/com/yourorg/middleware/ConfigLoader.java`).

## Compile

```bash
cd ThreatContextStoreTester
javac -cp "../ThreatContextStore/lib/*" TCSTester.java
```

## Run

```bash
# Windows
java -cp ".;../ThreatContextStore/lib/*" TCSTester

# Linux/macOS
# java -cp ".:../ThreatContextStore/lib/*" TCSTester
```

## Usage Examples

### Example 1: Send 5 Random Alerts
```
Choose option (1 or 2): 1
Choose option: 1
How many alerts to send? 5
```

### Example 2: Send Continuous Alerts
```
Choose option (1 or 2): 1
Choose option: 3
[Press any key and Enter to stop]
```

### Example 3: Query High Severity Alerts
```
Choose option (1 or 2): 2

Enter filter field: severity
Enter filter value: high
Add another filter? (y/n): n

Order by field (default: timestamp): [Enter]
Order direction (DESC/ASC, default: DESC): [Enter]
Limit (default: 10): 20
```

### Example 4: Query with Multiple Filters
```
Choose option (1 or 2): 2

Enter filter field: severity
Enter filter value: high
Add another filter? (y/n): y

Enter filter field: alert_type
Enter filter value: ransomware_detection
Add another filter? (y/n): n

Limit (default: 10): 5
```

## Random Alert Fields

The tester generates random alerts with:
- **Timestamps**: Current time in Manila timezone (GMT+8)
- **Severities**: high, medium, low, critical
- **Alert Types**: ransomware_detection, malware_execution, intrusion_attempt, ddos_attack, data_exfiltration, privilege_escalation, brute_force_attack, sql_injection, xss_attack, command_injection
- **Random IPs**: 192.168.x.x, 10.0.x.x ranges
- **Random Hosts**: host-192.168.x.x format
- **Random Paths**: Both Windows (C:\Users\...) and Linux (/tmp/...)
- **Threat Scores**: 0-99
- **Protocols**: TCP, UDP, ICMP, HTTP, HTTPS

## How It Works

1. **Alerts**: Sent directly to RabbitMQ `alerts_queue`
   - ThreatContextStore listener picks them up
   - Stores in PostgreSQL database

2. **Queries**: Sent to RabbitMQ `alerts_queue` with `message_type: "query"`
   - ThreatContextStore executes the query
   - Sends each result as `query_response` to `query_response_queue`
   - Writes responses to `ThreatContextStore/query_responses/` directory as `{event_id}.json` files

## Testing ThreatContextStore

### Full Test Flow:

1. **Start ThreatContextStore:**
   ```bash
   cd ../ThreatContextStore
   java -cp "target/classes:lib/*" com.yourorg.middleware.ThreatContextStoreMain
   ```

2. **Send Test Alerts (in another terminal):**
   ```bash
   cd ThreatContextStoreTester
   java -cp ".:../ThreatContextStore/lib/*" TCSTester
   # Choose 1, then 2 (send 10 alerts)
   ```

3. **Query Alerts:**
   ```bash
   java -cp ".:../ThreatContextStore/lib/*" TCSTester
   # Choose 2, filter by severity: high
   ```

4. **Verify Results:**
   - Check ThreatContextStore console for processing logs
   - Check `../ThreatContextStore/query_responses/` for result files
   - Query database: `psql -U postgres -h 192.168.171.145 -d wazuhdb -c "SELECT COUNT(*) FROM wazuh_alerts;"`

## Notes

- All messages use **Manila timezone (GMT+8)** for timestamps
- All alerts have `source_module: "TCSTester"` for easy identification
- 0.5 second delay between each alert to avoid overwhelming the system
- Query responses are written to **ThreatContextStore's** `query_responses/` directory (not this directory)
- Each query response is saved as `{event_id}.json` in `ThreatContextStore/query_responses/`
- This directory's `query_responses/` is just a placeholder/reference

