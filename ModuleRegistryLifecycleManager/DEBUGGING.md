# PostgreSQL Remote Connection Setup

## Problem
`psql -h 192.168.1.7 -p 5432 -U postgres -d wazuhdb` fails to connect. (localhost ip does not connect)
```
╔════════════════════════════════════════════════╗
║  ModuleRegistry & Lifecycle Manager v1.0      ║
╚════════════════════════════════════════════════╝

Purpose: Central hub for User-Defined Module management
- Registers and tracks UDMs
- Broadcasts alerts from UDMs to system queues
- Routes workflow commands to appropriate UDMs
- Monitors UDM health and availability

Configuration:
✅ Loaded configuration from config.properties
  RabbitMQ: 192.168.1.7:5672
  Database: 192.168.1.7:5432/wazuhdb
  Workflow Queue: workflow_queue
  Alerts Queue: alerts_queue
  Response Queue: workflow_response_queue

🔧 Initializing ModuleRegistry...
📂 Loading modules from database...
❌ Failed to load modules from database: Connection to 192.168.1.7:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

🚀 Starting components...


✅ ModuleRegistryAndLifecycleManager is running
```

## Diagnosis

Check if PostgreSQL only listens on localhost:

```bash
sudo ss -tlnp | grep 5432
```

**Problem:**
```
LISTEN 0  200  127.0.0.1:5432  0.0.0.0:*
```

**Expected:**
```
LISTEN 0  200  0.0.0.0:5432  0.0.0.0:*
```

---

## Solution

### 1. Edit postgresql.conf

```bash
sudo nano /etc/postgresql/*/main/postgresql.conf
```

Change:
```
listen_addresses = '*'
```

### 2. Edit pg_hba.conf

```bash
sudo nano /etc/postgresql/*/main/pg_hba.conf
```

Add after IPv4 local connections:
```
host    all    all    192.168.1.0/24    scram-sha-256
```

**Options:**
- `192.168.1.0/24` - Entire subnet
- `192.168.1.10/32` - Single IP
- `0.0.0.0/0` - All IPs (not recommended)

### 3. Restart PostgreSQL

```bash
sudo systemctl restart postgresql
```

### 4. Verify

```bash
sudo ss -tlnp | grep 5432
```

Should show `0.0.0.0:5432`

### 5. Test Connection

```bash
psql -h 192.168.1.7 -p 5432 -U postgres -d wazuhdb
```

---

## Troubleshooting

**Verify the following if it still does not connect**

Check firewall:
```bash
sudo ufw allow from 192.168.1.0/24 to any port 5432
```

Test port:
```bash
nc -zv 192.168.1.7 5432
```

Check logs:
```bash
sudo tail -f /var/log/postgresql/postgresql-*-main.log
```

---

## Quick Checklist

- [ ] `listen_addresses = '*'` in postgresql.conf
- [ ] Network rule added to pg_hba.conf
- [ ] PostgreSQL restarted
- [ ] Listening on `0.0.0.0:5432`
- [ ] Firewall allows port 5432
- [ ] Connection test succeeds
