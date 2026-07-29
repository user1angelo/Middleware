#!/usr/bin/env python3
"""
One file to simulate messages from every data source this SDK integrates with, "as if" each
real tool (Suricata, Maltrail, Fail2ban, Sysmon) were already installed and configured.

Pipeline each source demonstrates: simulated message -> SDK-built module -> ModuleRegistry ->
WorkflowEngine -> action (SDN isolation for Suricata/Maltrail, console notification for
Fail2ban/Sysmon).

Usage:
    python3 scripts/simulate_all_sources.py --source suricata
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


def simulate_suricata(args) -> None:
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
    append_line(args.eve_path, json.dumps(event), "suricata")


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
    "maltrail": simulate_maltrail,
    "fail2ban": simulate_fail2ban,
    "sysmon": simulate_sysmon,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, choices=list(SIMULATORS.keys()) + ["all"],
                         help="which data source to simulate a message from")

    parser.add_argument("--eve-path", default="/var/log/suricata/eve.json",
                         help="Suricata eve.json path to append to (default matches suricata-module.properties)")

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
