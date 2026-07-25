#!/usr/bin/env python3
"""
Send a single UDP JSON packet matching Maltrail's Logstash-style event schema,
to simulate a Maltrail sensor for testing MaltrailModule without standing up
real Maltrail infrastructure.

Usage:
    python scripts/send_test_maltrail_event.py [--host HOST] [--port PORT] [--severity SEVERITY]

By default sends a high-severity ransomware-trail hit that should match
WorkflowEngine/workflows/ransomware/maltrail_ransomware_isolate.yml once
MaltrailModule -> RabbitMQ -> WorkflowEngine are all running.
"""

import argparse
import json
import socket
import time


def build_event(severity: str) -> dict:
    now = int(time.time())
    return {
        "timestamp": now,
        "sensor": "test-sensor-01",
        "severity": severity,
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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="localhost", help="MaltrailModule host (default: localhost)")
    parser.add_argument("--port", type=int, default=8481, help="MaltrailModule UDP port (default: 8481, matches maltrail-module.properties)")
    parser.add_argument("--severity", default="high", help="severity value to send (default: high)")
    args = parser.parse_args()

    event = build_event(args.severity)
    payload = json.dumps(event).encode("utf-8")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.sendto(payload, (args.host, args.port))
    finally:
        sock.close()

    print(f"Sent UDP packet to {args.host}:{args.port}")
    print(json.dumps(event, indent=2))


if __name__ == "__main__":
    main()
