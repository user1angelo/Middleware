# PRTG–OpenDaylight Integration Event Model

Version: 0.1 (Akira PoC)

This document describes the events used when integrating PRTG and OpenDaylight through the SOAR SDK and workflow engine. It assumes two user-defined modules:

- **PRTGModule** – receives HTTP notifications from PRTG and publishes standardized events (`alerts.host.prtg`).
- **OpenDaylightModule** – subscribes to mitigation-related events and pushes isolation rules to OpenDaylight via RESTCONF.

Workflows in `WorkflowEngine/` connect these modules (e.g. `ransomware_detection_prtg.yml`).

All events follow the SDK JSON envelope described in `SDK_Detailed_Context.md`:

```json
{
  "event_id": "<uuid>",
  "timestamp": "<iso8601>",
  "event_type": "<type>",
  "source_module": "<moduleName>",
  "payload": { /* event-specific data */ }
}
```

---

## 1. Event Overview

| Event Type                | Direction                  | Producer        | Consumer(s)                  | Purpose                                  |
|--------------------------|----------------------------|-----------------|------------------------------|------------------------------------------|
| `alerts.host.prtg`       | PRTG → SOAR                | PRTGModule      | Workflow engine, webapp      | Normalize PRTG detections into SOAR      |
| `INITIATE_MITIGATION`    | Workflow → SDN/ODL module  | Workflow engine | OpenDaylightModule           | Request host isolation (MAC/IP block)    |
| `SDN_INSTALL_FLOW`       | Workflow → SDN/ODL module  | Workflow engine | OpenDaylightModule           | (Optional) low-level SDN flow install    |
| `SDN_FLOW_INSTALLED`     | SDN/ODL module → SOAR      | OpenDaylightModule | Workflow engine, webapp   | Confirm quarantine rule was installed    |
| `AUDIT_LOG`              | Any → webapp/log pipeline  | PRTGModule / OpenDaylightModule / workflows | webapp | Human-readable entries for dashboard     |

For the initial Akira PoC, the **minimal required events** are:

- `alerts.host.prtg`
- `INITIATE_MITIGATION`
- `AUDIT_LOG`

`SDN_INSTALL_FLOW` and `SDN_FLOW_INSTALLED` are optional, but align with existing SDN workflows like `flow-based-host-isolation-workflow.yml`.

---

## 2. `alerts.host.prtg`

### 2.1 Purpose

Represents a **host-level security alert from PRTG**, normalized into the SOAR JSON schema. The Akira PoC will use this to detect suspected ransomware activity and trigger isolation workflows.

### 2.2 Producer

- **Module:** `PRTGModule` (user-defined)
- **Source:** HTTP notification ("HTTP Action") from PRTG

### 2.3 Consumers

- Workflow engine (e.g. `ransomware_detection_prtg.yml`)
- Webapp (for dashboard alert list)

### 2.4 Event Envelope

- `event_type`: `"alerts.host.prtg"`
- `source_module`: e.g. `"PRTGModule"`

### 2.5 Payload Fields (proposed)

| Field              | Type   | Required | Description                                            |
|--------------------|--------|----------|--------------------------------------------------------|
| `host_ip`          | String | Yes      | IP address of the affected host (from PRTG)           |
| `host_name`        | String | No       | Device/host name in PRTG                              |
| `mac_address`      | String | No       | Host MAC address, if provided by PRTG / lookup        |
| `sensor_name`      | String | Yes      | PRTG sensor name that triggered the alert             |
| `sensor_id`        | String | No       | PRTG sensor ID                                        |
| `status`           | String | Yes      | PRTG status (`Up`, `Down`, `Warning`, `Error`, etc.)  |
| `message`          | String | Yes      | PRTG message / status text                            |
| `severity`         | String | No       | Normalized severity (`low`, `medium`, `high`, `critical`) |
| `ransomware_family`| String | No       | If known, e.g. `"Akira"`; may be `null`/absent for generic detection |
| `prtg_device_id`   | String | No       | PRTG device ID                                        |
| `prtg_group`       | String | No       | PRTG group or location                                |
| `raw`              | Object | No       | Raw PRTG key/value pairs for debugging                |

**Example:**

```json
{
  "event_id": "c5e2a8a3-1c3f-4d89-9d7f-9eac22d8a001",
  "timestamp": "2025-07-21T10:35:12.452Z",
  "event_type": "alerts.host.prtg",
  "source_module": "PRTGModule",
  "payload": {
    "host_ip": "192.168.10.42",
    "host_name": "win-client-42",
    "mac_address": "00:11:22:33:44:55",
    "sensor_name": "Akira Ransomware Detector",
    "sensor_id": "1234",
    "status": "Error",
    "message": "Akira ransomware behavior pattern detected",
    "severity": "high",
    "ransomware_family": "Akira",
    "prtg_device_id": "235",
    "prtg_group": "Workstations",
    "raw": {
      "prtg_placeholder_example": "..."
    }
  }
}
```

> Note: the exact mapping from PRTG placeholders → payload fields will be tuned once PRTG is available. For now, the module should aim to populate as many of the above as possible.

---

## 3. `INITIATE_MITIGATION`

### 3.1 Purpose

Generic **mitigation request** to isolate or otherwise protect a host. This is already used by `sdn_block_mac.yml` and can be reused for PRTG-driven ransomware cases.

In the Akira PoC, `ransomware_detection_prtg.yml` will publish an `INITIATE_MITIGATION` event that tells the OpenDaylight module to block traffic from a suspected host.

### 3.2 Producer

- Workflow engine (e.g. `ransomware_detection_prtg.yml`)

### 3.3 Consumers

- `OpenDaylightModule` (user-defined module controlling OpenDaylight)

### 3.4 Event Envelope

- `event_type`: `"INITIATE_MITIGATION"`
- `source_module`: workflow engine (internal name)

### 3.5 Payload Fields (reusing / extending `sdn_block_mac.yml`)

| Field           | Type   | Required | Description                                             |
|-----------------|--------|----------|---------------------------------------------------------|
| `targetHost`    | String | Yes      | Primary host identifier (IP or hostname)               |
| `targetMac`     | String | No       | Host MAC address, if known                             |
| `hostId`        | String | No       | Logical host ID (if available)                         |
| `action`        | String | Yes      | Mitigation action, e.g. `"BLOCK_MAC"`, `"BLOCK_IP"`   |
| `sdn_controller`| String | Yes      | Target SDN controller, e.g. `"opendaylight"`          |
| `duration`      | String | No       | Human-readable duration (e.g. `"60 minutes"`)         |
| `priority`      | String | No       | `"low"`, `"medium"`, `"high"`                        |
| `justification` | String | Yes      | Why this mitigation is being applied                    |
| `family`        | String | No       | Ransomware family if known (e.g. `"Akira"`)           |
| `sourceEventId` | String | No       | `event_id` of the triggering `alerts.host.prtg` event  |

**Example from `ransomware_detection_prtg.yml`:**

```json
{
  "event_id": "b83c8b3a-6c4c-4d2e-8a7e-1cdd83f96910",
  "timestamp": "2025-07-21T10:36:00.000Z",
  "event_type": "INITIATE_MITIGATION",
  "source_module": "WorkflowEngine",
  "payload": {
    "targetHost": "192.168.10.42",
    "targetMac": "00:11:22:33:44:55",
    "hostId": "host-192.168.10.42",
    "action": "BLOCK_MAC",
    "sdn_controller": "opendaylight",
    "duration": "60 minutes",
    "priority": "high",
    "justification": "PRTG Akira ransomware behavior detected on host 192.168.10.42",
    "family": "Akira",
    "sourceEventId": "c5e2a8a3-1c3f-4d89-9d7f-9eac22d8a001"
  }
}
```

The **OpenDaylightModule** should:

1. Subscribe to `INITIATE_MITIGATION` events.
2. Filter on `payload.action` (e.g. `BLOCK_MAC`, `BLOCK_IP`) and `payload.sdn_controller == "opendaylight"`.
3. Translate this into one or more RESTCONF calls to OpenDaylight to enforce the block.
4. Optionally publish `SDN_FLOW_INSTALLED` and `AUDIT_LOG` events (see sections 4 and 5).

---

## 4. `SDN_INSTALL_FLOW` and `SDN_FLOW_INSTALLED` (optional, SDN-focused pattern)

These events are already referenced by `flow-based-host-isolation-workflow.yml` and represent a more detailed SDN workflow.

### 4.1 `SDN_INSTALL_FLOW` (Request to install a specific flow)

- **Producer:** Workflow engine
- **Consumer:** OpenDaylightModule
- **event_type:** `"SDN_INSTALL_FLOW"`

**Proposed payload fields (based on `flow-based-host-isolation-workflow.yml`):**

| Field       | Type   | Required | Description                                   |
|------------|--------|----------|-----------------------------------------------|
| `switchId` | String | Yes      | Target switch/node ID in ODL                  |
| `tableId`  | Int    | Yes      | Flow table ID (e.g. `0`)                      |
| `priority` | Int    | Yes      | Flow priority                                 |
| `flowId`   | String | Yes      | Unique ID for this flow (e.g. `quarantine-<ip>`)|
| `match`    | Object | Yes      | Match fields (e.g. `ipv4_src`, `eth_type`)    |
| `instructions` | Array | Yes   | Actions (e.g. drop)                           |
| `hardTimeout` | Int | No       | Flow hard timeout                             |
| `idleTimeout` | Int | No       | Flow idle timeout                             |

**Example:**

```json
{
  "event_type": "SDN_INSTALL_FLOW",
  "payload": {
    "switchId": "openflow:1",
    "tableId": 0,
    "priority": 32768,
    "flowId": "quarantine-192.168.10.42",
    "match": {
      "ipv4_src": "192.168.10.42",
      "eth_type": "0x0800"
    },
    "instructions": [
      {
        "type": "APPLY_ACTIONS",
        "actions": [ { "type": "DROP" } ]
      }
    ],
    "hardTimeout": 0,
    "idleTimeout": 0
  }
}
```

The **OpenDaylightModule** would translate this into the corresponding RESTCONF `PUT` to OpenDaylight.

### 4.2 `SDN_FLOW_INSTALLED` (Confirmation)

- **Producer:** OpenDaylightModule
- **Consumers:** Workflow engine, webapp
- **event_type:** `"SDN_FLOW_INSTALLED"`

**Payload (proposed):**

| Field     | Type   | Required | Description                         |
|----------|--------|----------|-------------------------------------|
| `flowId` | String | Yes      | Flow identifier                     |
| `switchId` | String | Yes    | Switch/node ID                      |
| `status` | String | Yes      | `"ACTIVE"`, `"FAILED"`, etc.       |
| `details`| String | No       | Additional info / error messages    |

This can be used both for automatic verification (in workflows) and for audit logging.

---

## 5. `AUDIT_LOG`

### 5.1 Purpose

Provide a **human-readable log entry** that the webapp can display on the dashboard. This is used for both detection and mitigation actions.

### 5.2 Producers

- `PRTGModule` – when it publishes an `alerts.host.prtg` that meets ransomware conditions.
- `OpenDaylightModule` – when it enforces or fails to enforce a mitigation.
- Workflows – to record higher-level decisions.

### 5.3 Consumers

- Webapp (via RabbitMQ client and whatever logging pipeline you configure).

### 5.4 Event Envelope

- `event_type`: `"AUDIT_LOG"`

### 5.5 Payload Fields (proposed)

| Field       | Type   | Required | Description                                           |
|------------|--------|----------|-------------------------------------------------------|
| `action`    | String | Yes      | Short action code (e.g. `"RANSOMWARE_ALERT"`)        |
| `source`    | String | Yes      | `"PRTG"`, `"OpenDaylight"`, `"WorkflowEngine"`, etc. |
| `severity`  | String | No       | `"info"`, `"warning"`, `"error"`, `"critical"`      |
| `host_ip`   | String | No       | Involved host IP                                     |
| `host_name` | String | No       | Involved host name                                   |
| `family`    | String | No       | Ransomware family (e.g. `"Akira"`)                   |
| `message`   | String | Yes      | Human-readable message for the dashboard             |
| `related_event_id` | String | No | Links back to triggering event                       |

**Example – alert from PRTG:**

```json
{
  "event_type": "AUDIT_LOG",
  "payload": {
    "action": "RANSOMWARE_ALERT",
    "source": "PRTG",
    "severity": "critical",
    "host_ip": "192.168.10.42",
    "host_name": "win-client-42",
    "family": "Akira",
    "message": "PRTG detected Akira-like ransomware behavior on host 192.168.10.42",
    "related_event_id": "c5e2a8a3-1c3f-4d89-9d7f-9eac22d8a001"
  }
}
```

**Example – successful isolation via OpenDaylight:**

```json
{
  "event_type": "AUDIT_LOG",
  "payload": {
    "action": "HOST_ISOLATED",
    "source": "OpenDaylight",
    "severity": "info",
    "host_ip": "192.168.10.42",
    "message": "Host 192.168.10.42 isolated via OpenDaylight MAC/IP block",
    "related_event_id": "b83c8b3a-6c4c-4d2e-8a7e-1cdd83f96910"
  }
}
```

These `AUDIT_LOG` events should be surfaced in the webapp dashboard to show ransomware alerts and subsequent mitigation actions.

---

## 6. Workflow: `ransomware_detection_prtg.yml` (Concept)

The PRTG-based detection workflow will live under `WorkflowEngine/workflows/ransomware/` and could be named `ransomware_detection_prtg.yml`.

### 6.1 Trigger

- `event_type: "alerts.host.prtg"`
- Condition (initial suggestion):
  - `{{ trigger.payload.ransomware_family == 'Akira' }}` **OR**
  - `{{ trigger.payload.message contains 'ransomware' }}` (until Akira-specific logic is known)

### 6.2 Minimal Steps (baseline PoC)

1. **Publish mitigation request**
   - Action: publish `INITIATE_MITIGATION` with `BLOCK_MAC` or `BLOCK_IP` depending on available data.
2. **Write audit log**
   - Action: publish `AUDIT_LOG` describing the detection and requested isolation.

This keeps the first iteration simple while allowing more advanced workflows (e.g. using `SDN_INSTALL_FLOW`) later.

---

## 7. Module Responsibilities (Summary)

### 7.1 PRTGModule (user-defined module)

- Implements `PluggableModule` from the SDK.
- On `initialize(CoreSystemApi api)`:
  - Starts an HTTP listener endpoint for PRTG HTTP Actions (exact port/path configurable).
  - Parses incoming PRTG notifications and builds `alerts.host.prtg` events.
  - Optionally publishes an `AUDIT_LOG` when a ransomware-like alert is received.
- Uses `api.publishEvent(Event.of("alerts.host.prtg", payload))` for normalized host alerts.

### 7.2 OpenDaylightModule (user-defined module)

- Implements `PluggableModule` from the SDK.
- On `initialize(CoreSystemApi api)`:
  - Subscribes to `INITIATE_MITIGATION` (and optionally `SDN_INSTALL_FLOW`).
  - For relevant requests (e.g. `action == 'BLOCK_MAC'` or `BLOCK_IP`), calls OpenDaylight RESTCONF to install appropriate rules.
  - Publishes `AUDIT_LOG` entries and, optionally, `SDN_FLOW_INSTALLED` events to confirm success/failure.

---

## 8. Open Questions / To Be Confirmed

- Exact PRTG HTTP Action payload format and how to map placeholders to `alerts.host.prtg` fields.
- How to reliably obtain `mac_address` (directly from PRTG vs. separate lookup). If not available, fall back to IP-based blocking.
- Specific RESTCONF endpoints and models in OpenDaylight for MAC/IP blocking (flow tables, ACLs, or other applications).
- Final trigger condition for Akira versus generic ransomware in `ransomware_detection_prtg.yml`.

This document should be updated as PRTG and OpenDaylight behavior is tested and the integration is refined.
