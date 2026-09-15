#!/usr/bin/env python3
"""
Send sample logs to Loki via its HTTP push API.

Usage:
    pip install requests
    python send_sample_logs.py --url http://localhost:3100 --count 50
    python send_sample_logs.py --stream           # keep sending logs every second
"""

import argparse
import json
import os
import random
import time
from datetime import datetime, timezone

import requests

LEVELS = ["INFO", "WARN", "ERROR", "DEBUG"]
SERVICES = ["auth-service", "orders-service", "payments-service", "gateway"]

MESSAGES = {
    "INFO": [
        "Request handled successfully",
        "User {user} logged in",
        "Cache warmed up in {ms}ms",
        "Health check passed",
    ],
    "WARN": [
        "Slow response time: {ms}ms",
        "Retrying request to downstream service",
        "Deprecated endpoint called by {user}",
    ],
    "ERROR": [
        "Failed to connect to database",
        "Unhandled exception processing request for {user}",
        "Timeout calling downstream service after {ms}ms",
    ],
    "DEBUG": [
        "Payload validated for {user}",
        "Trace id generated: {trace_id}",
    ],
}


def build_log_line(level: str) -> str:
    template = random.choice(MESSAGES[level])
    return template.format(
        user=f"user_{random.randint(1, 999)}",
        ms=random.randint(5, 900),
        trace_id=f"{random.getrandbits(64):016x}",
    )


def push_logs(loki_url: str, count: int):
    streams = []

    for _ in range(count):
        service = random.choice(SERVICES)
        level = random.choice(LEVELS)
        line = build_log_line(level)
        ts_ns = str(time.time_ns())

        streams.append(
            {
                "stream": {
                    "service": service,
                    "level": level,
                    "job": "sample-log-generator",
                },
                "values": [[ts_ns, line]],
            }
        )

    payload = {"streams": streams}

    resp = requests.post(
        f"{loki_url}/loki/api/v1/push",
        headers={"Content-Type": "application/json"},
        data=json.dumps(payload),
        timeout=10,
    )

    if resp.status_code == 204:
        print(f"[{datetime.now(timezone.utc).isoformat()}] Sent {count} log lines OK")
    else:
        print(f"Error {resp.status_code}: {resp.text}")


def main():
    parser = argparse.ArgumentParser(description="Send sample logs to Loki")
    parser.add_argument(
        "--url", default=os.environ.get("LOKI_URL", "http://localhost:3100"), help="Loki base URL"
    )
    parser.add_argument(
        "--count", type=int, default=int(os.environ.get("LOG_COUNT", 20)), help="Logs per batch"
    )
    parser.add_argument(
        "--stream",
        action="store_true",
        default=os.environ.get("LOG_STREAM", "false").lower() == "true",
        help="Keep sending a batch every --interval seconds",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=float(os.environ.get("LOG_INTERVAL", 2.0)),
        help="Seconds between batches (with --stream)",
    )
    args = parser.parse_args()

    if args.stream:
        print(f"Streaming {args.count} logs every {args.interval}s to {args.url} (Ctrl+C to stop)")
        try:
            while True:
                push_logs(args.url, args.count)
                time.sleep(args.interval)
        except KeyboardInterrupt:
            print("Stopped.")
    else:
        push_logs(args.url, args.count)


if __name__ == "__main__":
    main()