#!/usr/bin/env python3
"""
One file to simulate messages from every data source this SDK integrates with, "as if" each
real tool (Suricata, Zeek, Maltrail, Fail2ban, Sysmon) were already installed and configured.

Pipeline each source demonstrates: simulated message -> SDK-built module -> ModuleRegistry ->
WorkflowEngine -> action (SDN isolation for Suricata/Zeek/Maltrail, console notification for
Fail2ban/Sysmon).

IMPORTANT - Suricata and Zeek use HTTP, not a local file/UDP:
In the real deployment, Suricata and Zeek run on a separate "switch VM" and are relayed to this
system's SuricataHttpModule/ZeekHttpModule over HTTP by a forwarder script
(ids_http_forwarder.py) - they are NOT tailed from a local file on this machine. So simulating
them here means POSTing directly to those modules' own embedded HTTP endpoints, matching exactly
what that forwarder sends. Maltrail, Fail2ban, and Sysmon are unaffected by this - they're
standalone modules that genuinely do listen on a local UDP port / tail a local file directly.

Usage:
    python3 scripts/simulate_all_sources.py --source suricata
    python3 scripts/simulate_all_sources.py --source zeek
    python3 scripts/simulate_all_sources.py --source maltrail
    python3 scripts/simulate_all_sources.py --source fail2ban
    python3 scripts/simulate_all_sources.py --source sysmon
    python3 scripts/simulate_all_sources.py --source all

Requires the corresponding module(s) to already be running (and RabbitMQ/WorkflowEngine/
ModuleRegistry for anything past the module itself) - see TESTING_GUIDE.md.
"""

import argparse
import json
import os
import socket
import time
import urllib.error
import urllib.request


def send_udp(host: str, port: int, payload: dict, label: str) -> None:
    data = json.dumps(payload).encode("utf-8")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.sendto(data, (host, port))
    finally:
        sock.close()
    print(f"[{label}] Sent UDP packet to {host}:{port}")
    print(json.dumps(payload, indent=2))


def append_line(path: str, line: str, label: str) -> None:
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(line.rstrip("\n") + "\n")
    print(f"[{label}] Appended line to {path}:")
    print(f"  {line}")


def post_json(url: str, payload: dict, label: str, timeout_s: float = 5.0) -> None:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url=url, data=body, method="POST", headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            status = resp.status
            resp.read()
    except urllib.error.HTTPError as e:
        print(f"[{label}] HTTP POST to {url} returned {e.code}: {e.reason}")
        print(json.dumps(payload, indent=2))
        return
    except urllib.error.URLError as e:
        print(f"[{label}] FAILED to reach {url}: {e.reason}")
        print("  (is the module's embedded HTTP server actually running and reachable "
              "from where this script is running?)")
        return
    print(f"[{label}] POSTed to {url} -> HTTP {status}")
    print(json.dumps(payload, indent=2))


def simulate_suricata(args) -> None:
    # Matches SuricataHttpModule's embedded HTTP endpoint exactly (default port 8090, path
    # /suricata/alerts) - this is the same eve.json-shaped JSON body ids_http_forwarder.py
    # reads from a real Suricata's eve.json on the switch VM and POSTs verbatim, so this is
    # a direct stand-in for that forwarder, not a different mechanism.
    event = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S.000000+0000", time.gmtime()),
        "event_type": "alert",
        "src_ip": "10.0.0.77",
        "dest_ip": "203.0.113.5",
        "src_port": 44521,
        "dest_port": 445,
        "proto": "TCP",
        "alert": {
            "signature": "ET TROJAN Ryuk Ransom Note",
            "signature_id": 2024123,
            "severity": 1,
            "category": "A Network Trojan was Detected",
        },
    }
    post_json(args.suricata_http_url, event, "suricata")


def simulate_zeek(args) -> None:
    # Matches ZeekHttpModule's embedded HTTP endpoint exactly (default port 8091, path
    # /zeek/notices). The JSON shape here is the *already-normalized* shape
    # ids_http_forwarder.py's _normalize_zeek_notice()/zeek_smb_normalizer() produce from raw
    # Zeek notice.log/smb_mapping.log entries - not Zeek's raw id.orig_h-style field names -
    # since that's the exact shape ZeekHttpModule's own ZeekNotice class deserializes.
    notice = {
        "note": "SMB::Mapping",
        "msg": "SMB share mapping observed to a known-bad host",
        "sub": "ransomware staging",
        "src": "10.0.0.88",
        "dst": "203.0.113.9",
        "p": 51823,
        "dst_port": 445,
        "proto": "tcp",
        "uid": "CXt1sq3hMoWKGpKV4h",
        "actions": "Notice::ACTION_LOG",
    }
    post_json(args.zeek_http_url, notice, "zeek")


def simulate_maltrail(args) -> None:
    event = {
        "timestamp": int(time.time()),
        "sensor": "test-sensor-01",
        "severity": "high",
        "src_ip": "10.0.0.55",
        "src_port": 51234,
        "dst_ip": "203.0.113.9",
        "dst_port": 443,
        "proto": "tcp",
        "type": "ip",
        "trail": "203.0.113.9",
        "info": "ransomware",
        "reference": "abuse.ch",
    }
    send_udp(args.maltrail_host, args.maltrail_port, event, "maltrail")


def simulate_fail2ban(args) -> None:
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S,000", time.localtime())
    line = f"{timestamp} fail2ban.actions        [12345]: NOTICE  [sshd] Ban 203.0.113.66"
    append_line(args.fail2ban_log_path, line, "fail2ban")


def simulate_sysmon(args) -> None:
    event = {
        "event_id": 1,
        "computer": "WIN-HOST01",
        "timestamp": int(time.time()),
        "image": "C:\\Windows\\System32\\cmd.exe",
        "command_line": "vssadmin.exe delete shadows /all /quiet",
        "parent_image": "C:\\Windows\\explorer.exe",
        "user": "WIN-HOST01\\Administrator",
    }
    send_udp(args.sysmon_host, args.sysmon_port, event, "sysmon")


SIMULATORS = {
    "suricata": simulate_suricata,
    "zeek": simulate_zeek,
    "maltrail": simulate_maltrail,
    "fail2ban": simulate_fail2ban,
    "sysmon": simulate_sysmon,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, choices=list(SIMULATORS.keys()) + ["all"],
                         help="which data source to simulate a message from")

    parser.add_argument("--suricata-http-url", default="http://localhost:8090/suricata/alerts",
                         help="SuricataHttpModule's HTTP endpoint (default matches "
                              "suricata-http-module.properties: port 8090, path /suricata/alerts)")

    parser.add_argument("--zeek-http-url", default="http://localhost:8091/zeek/notices",
                         help="ZeekHttpModule's HTTP endpoint - port 8091, path /zeek/notices "
                              "are its hardcoded defaults (no zeek-http-module.properties file "
                              "exists yet to override them; do not confuse with "
                              "zeek-module.properties, which configures the unrelated "
                              "standalone ZeekModule)")

    parser.add_argument("--maltrail-host", default="localhost")
    parser.add_argument("--maltrail-port", type=int, default=8481,
                         help="matches maltrail-module.properties maltrail.udp_port")

    parser.add_argument("--fail2ban-log-path", default="user-defined-modules/simulated_logs/fail2ban.log",
                         help="matches fail2ban-module.properties fail2ban.log_path, relative to repo root")

    parser.add_argument("--sysmon-host", default="localhost")
    parser.add_argument("--sysmon-port", type=int, default=8482,
                         help="matches sysmon-module.properties sysmon.udp_port")

    args = parser.parse_args()

    sources = list(SIMULATORS.keys()) if args.source == "all" else [args.source]
    for i, source in enumerate(sources):
        if i > 0:
            time.sleep(1)
        SIMULATORS[source](args)


if __name__ == "__main__":
    main()
