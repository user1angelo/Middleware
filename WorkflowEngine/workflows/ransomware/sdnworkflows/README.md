# SDN Workflows for Ransomware Detection and Mitigation

This directory contains OpenDaylight-oriented workflows that either:
- react to ransomware detections from host/network sensors, or
- trigger mitigations based on SDN telemetry-detected behaviors.

All YAMLs publish INITIATE_MITIGATION commands with `sdn_controller: opendaylight`. A ModuleRegistry/SDN adapter should translate these into OpenDaylight RESTCONF calls (installing flows, changing VLANs, shutting ports, etc.).

## Event Types and Expected Payloads

- Host-driven detections (from Wazuh/IDS):
  - `event_type: "alerts.host.wazuh"`
  - Expected fields (examples): `event_id`, `payload.host_id`, `payload.source_ip`, `payload.mac_address?`, `payload.severity`, `payload.alert_type`.
- SDN telemetry detections (from ODL adapter):
  - `event_type: "SDN_TELEMETRY_ALERT"`
  - Expected fields (examples):
    - Common: `source_ip`, `host_id` (if known), `telemetry_type`
    - SMB: `smb_bytes_1m`
    - Lateral: `dest_count_5m`
    - C2: `c2_ioc_match` (bool), `beacon_score` (0..1)
    - Exfil: `total_bytes_out_5m`
    - RDP: `rdp_conn_rate_1m`
    - DNS: `dns_fail_pct`, `avg_query_len`

Example SDN telemetry alert:
```json
{
  "message_type": "alert",
  "event_type": "SDN_TELEMETRY_ALERT",
  "payload": {
    "telemetry_type": "smb_activity",
    "source_ip": "192.168.1.55",
    "host_id": "host-192.168.1.55",
    "smb_bytes_1m": 157286400
  }
}
```

## Mitigation Workflows (host-driven)

- sdn_isolate_vlan.yml
  - Action: `ISOLATE_VLAN` to quarantine VLAN (default `999`).
- sdn_block_ip.yml
  - Action: `BLOCK_IP` (fabric-wide drop; `direction: both`).
- sdn_block_mac.yml
  - Action: `BLOCK_MAC` (access-layer L2 drop; needs `payload.mac_address`).
- sdn_shut_port.yml
  - Action: `SHUT_PORT` (admin-down switch port; needs `switch_id`, `port_id`).
- sdn_rate_limit.yml
  - Action: `RATE_LIMIT` egress (default `1 Mbps`, `burst 128 KB`).
- sdn_block_services_rdp_smb.yml
  - Action: `BLOCK_SERVICES` for TCP 3389 (RDP) and 445 (SMB), `direction: both`.
- sdn_block_egress_internet.yml
  - Action: `BLOCK_EGRESS_INTERNET` (allow RFC1918 by default).
- sdn_allow_mgmt_only.yml
  - Action: `ALLOWLIST_ONLY` to specified mgmt CIDRs (EDR/jump/patch subnets).
- sdn_restrict_dns.yml
  - Action: `RESTRICT_DNS` to internal resolvers.
- sdn_block_http_https.yml
  - Action: `BLOCK_PORTS` 80/443 egress.
- sdn_block_lateral.yml
  - Action: `BLOCK_LATERAL` (deny east–west except mgmt allowlist).
- sdn_mirror_traffic.yml
  - Action: `MIRROR_TRAFFIC` (SPAN to sensor target).

## Detection-Driven Workflows (SDN telemetry)

- sdn_detect_smb_surge.yml
  - Trigger: `telemetry_type == 'smb_activity'` and `smb_bytes_1m >= 100 MB`.
  - Mitigations: `BLOCK_SERVICES` (SMB/445, both directions) + `MIRROR_TRAFFIC`.
- sdn_detect_lateral_scan.yml
  - Trigger: `telemetry_type == 'east_west_fanout'` and `dest_count_5m >= 20`.
  - Mitigations: `BLOCK_LATERAL` + `RATE_LIMIT` egress.
- sdn_detect_c2_beacon.yml
  - Trigger: `telemetry_type == 'c2_activity'` and (`c2_ioc_match == true` or `beacon_score >= 0.9`).
  - Mitigations: `BLOCK_EGRESS_INTERNET` + `RESTRICT_DNS`.
- sdn_detect_data_exfil.yml
  - Trigger: `telemetry_type == 'egress_volume'` and `total_bytes_out_5m >= 1 GB`.
  - Mitigations: `RATE_LIMIT` egress + `BLOCK_PORTS` 80/443 egress.
- sdn_detect_rdp_sweep.yml
  - Trigger: `telemetry_type == 'rdp_activity'` and `rdp_conn_rate_1m >= 30`.
  - Mitigations: `BLOCK_SERVICES` (RDP/3389, both) + optional `ISOLATE_VLAN`.
- sdn_detect_dns_tunnel.yml
  - Trigger: `telemetry_type == 'dns_activity'` and (`dns_fail_pct >= 50` or `avg_query_len >= 100`).
  - Mitigations: `RESTRICT_DNS` + `BLOCK_EGRESS_INTERNET` (RFC1918 allowlist).

## Defaults and Tunables

- Quarantine VLAN: `999`
- Management allowlist (examples): `192.168.100.0/24`, `192.168.200.0/24`, `192.168.210.0/24`
- Internal DNS: `192.168.50.53`, `192.168.50.54`
- Mirror target: `sensor-mirror-1`
- Durations: typically `30–120 minutes` or `until_cleared`
- Priorities: `low | medium | high | critical`

Adjust thresholds and parameters directly in each YAML to fit your environment.

## How Enforcement Maps to OpenDaylight

All workflows emit `INITIATE_MITIGATION` with an action. The SDN adapter should call ODL RESTCONF to:
- `BLOCK_IP`, `BLOCK_PORTS`, `BLOCK_SERVICES`: install flow entries matching IP/port to drop.
- `BLOCK_MAC`: L2 drop flow at access switch.
- `BLOCK_EGRESS_INTERNET`: ACL/flows denying non-RFC1918 destinations.
- `BLOCK_LATERAL`: intra-subnet deny except allowlist.
- `ISOLATE_VLAN`: move endpoint to quarantine VLAN.
- `SHUT_PORT`: admin-down the switch port via NETCONF/OF.
- `RATE_LIMIT`: apply meter/policer and bind to host flows.
- `MIRROR_TRAFFIC`: configure SPAN/port-mirroring to `span_target`.
- `RESTRICT_DNS`: allow DNS only to approved servers, drop others.
- `ALLOWLIST_ONLY`: default deny, permit only `allowed_destinations`.

## Testing Tips

- Send representative `alerts.host.wazuh` and `SDN_TELEMETRY_ALERT` messages to `workflow_queue` and observe `workflow_response_queue` commands.
- Start with conservative thresholds, then tune to reduce false positives.
- Combine mitigations (e.g., `BLOCK_LATERAL` + `RESTRICT_DNS`) for layered defense.

## Safety and Rollback

- Prefer temporary actions (duration-based) and explicit allowlists for IR access.
- Quarantine VLAN and RDP/SMB blocks are high impact—apply with care.
- Ensure a manual clear/unblock process exists in your SDN adapter.

---

If you want this documentation to reflect your exact VLAN ID, DNS servers, and management subnets, update the values above and the corresponding YAMLs.
