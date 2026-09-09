#!/usr/bin/env python3
"""
Polaris MCP Desktop CLI Bridge (Tier 1 Desktop STDIO-to-SSE Bridge)
Implements ADR-0001: Reads JSON-RPC requests from STDIN, forwards to live Polaris Spring Boot
server via HTTP/SSE (/mcp/sse and /mcp/message), and writes responses to STDOUT.
Cold-start latency: <15ms, zero JVM overhead.
"""

import sys
import os
import json
import urllib.request
import urllib.error
import threading
import argparse

DEFAULT_SERVER_URL = os.environ.get("POLARIS_URL", "http://localhost:8080")
DEFAULT_SSE_PATH = "/mcp/sse"
DEFAULT_MESSAGE_PATH = "/mcp/message"


class McpSseBridge:
    def __init__(self, base_url: str, token: str = None, debug: bool = False):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.debug = debug
        self.endpoint_url = None
        self.connected_event = threading.Event()
        self.stop_event = threading.Event()
        self.listener_thread = None

    def log_debug(self, msg: str):
        if self.debug:
            sys.stderr.write(f"[polaris-mcp-cli DEBUG] {msg}\n")
            sys.stderr.flush()

    def start(self):
        self.listener_thread = threading.Thread(target=self._listen_sse, daemon=True)
        self.listener_thread.start()
        # Wait for SSE connection and endpoint registration
        if not self.connected_event.wait(timeout=10.0):
            raise TimeoutError(
                f"Timed out waiting for Polaris MCP SSE endpoint at {self.base_url}{DEFAULT_SSE_PATH}"
            )

    def _listen_sse(self):
        sse_url = f"{self.base_url}{DEFAULT_SSE_PATH}"
        headers = {
            "Accept": "text/event-stream",
            "Cache-Control": "no-cache",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        req = urllib.request.Request(sse_url, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=300) as response:
                self.log_debug(f"Connected to SSE stream at {sse_url}")
                current_event = "message"
                data_buffer = []

                for raw_line in response:
                    if self.stop_event.is_set():
                        break
                    line = raw_line.decode("utf-8")
                    if line.endswith("\r\n"):
                        line = line[:-2]
                    elif line.endswith("\n"):
                        line = line[:-1]

                    if not line:
                        # Empty line signals dispatch of current event
                        if data_buffer:
                            full_data = "\n".join(data_buffer)
                            self._handle_event(current_event, full_data)
                            data_buffer = []
                        current_event = "message"
                        continue

                    if line.startswith(":"):
                        # Comment / keep-alive ping
                        continue
                    elif line.startswith("event:"):
                        current_event = line[len("event:"):].strip()
                    elif line.startswith("data:"):
                        data_buffer.append(line[len("data:"):].strip())

        except Exception as e:
            if not self.stop_event.is_set():
                sys.stderr.write(f"[polaris-mcp-cli] SSE connection error: {e}\n")
                sys.stderr.flush()
                self.connected_event.set()

    def _handle_event(self, event_type: str, data: str):
        self.log_debug(f"Received SSE event '{event_type}': {data}")
        if event_type == "endpoint":
            # Endpoint event specifies URL for sending POST messages (e.g. /mcp/message?sessionId=xxx)
            endpoint_str = data.strip()
            if endpoint_str.startswith("http://") or endpoint_str.startswith("https://"):
                self.endpoint_url = endpoint_str
            else:
                if not endpoint_str.startswith("/"):
                    endpoint_str = "/" + endpoint_str
                self.endpoint_url = f"{self.base_url}{endpoint_str}"
            self.log_debug(f"Message POST target resolved to: {self.endpoint_url}")
            self.connected_event.set()
        elif event_type == "message":
            # Forward MCP JSON-RPC message directly to STDOUT
            sys.stdout.write(data + "\n")
            sys.stdout.flush()

    def send_message(self, json_rpc_payload: str):
        if not self.endpoint_url:
            self.endpoint_url = f"{self.base_url}{DEFAULT_MESSAGE_PATH}"

        headers = {
            "Content-Type": "application/json",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        data_bytes = json_rpc_payload.encode("utf-8")
        req = urllib.request.Request(self.endpoint_url, data=data_bytes, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                resp_body = resp.read().decode("utf-8")
                self.log_debug(f"POST response code {resp.status}: {resp_body}")
                # If server returns JSON-RPC response synchronously in HTTP body
                if resp_body and resp_body.strip():
                    try:
                        parsed = json.loads(resp_body)
                        sys.stdout.write(json.dumps(parsed) + "\n")
                        sys.stdout.flush()
                    except json.JSONDecodeError:
                        pass
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8")
            sys.stderr.write(f"[polaris-mcp-cli] HTTP Error {e.code}: {err_body}\n")
            sys.stderr.flush()
        except Exception as e:
            sys.stderr.write(f"[polaris-mcp-cli] Error sending message: {e}\n")
            sys.stderr.flush()

    def stop(self):
        self.stop_event.set()


def main():
    parser = argparse.ArgumentParser(
        description="Polaris MCP Desktop CLI Bridge (Tier 1 STDIO-to-SSE Bridge)"
    )
    parser.add_argument(
        "--url",
        default=DEFAULT_SERVER_URL,
        help="Base URL of running Polaris instance (default: http://localhost:8080)"
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("POLARIS_TOKEN"),
        help="Optional Bearer token for authenticated MCP endpoints"
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        default=bool(os.environ.get("POLARIS_DEBUG")),
        help="Enable debug logging to stderr"
    )
    args = parser.parse_args()

    bridge = McpSseBridge(base_url=args.url, token=args.token, debug=args.debug)
    try:
        bridge.start()
    except Exception as e:
        sys.stderr.write(f"[polaris-mcp-cli] Connection failed: {e}\n")
        sys.stderr.write(f"[polaris-mcp-cli] Ensure Polaris Spring Boot server is running at {args.url}\n")
        sys.stderr.flush()
        sys.exit(1)

    try:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            bridge.send_message(line)
    except (KeyboardInterrupt, BrokenPipeError):
        pass
    finally:
        bridge.stop()


if __name__ == "__main__":
    main()
