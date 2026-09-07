"""
Polaris MCP Server - Stdio Transport Loop
Implements standard Model Context Protocol (MCP) stdio JSON-RPC transport.
"""

import sys
import json
from .jsonrpc import handle_json_rpc
from ..telemetry import get_logger, shutdown_telemetry

logger = get_logger("polaris_mcp.server.stdio")


def run_stdio() -> None:
    """Runs standard MCP stdio JSON-RPC loop over sys.stdin / sys.stdout."""
    logger.info("Starting Polaris MCP Stdio server loop")
    try:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                req = json.loads(line)
                res = handle_json_rpc(req)
                if res is not None:
                    sys.stdout.write(json.dumps(res) + "\n")
                    sys.stdout.flush()
            except Exception as e:
                err = {
                    "jsonrpc": "2.0",
                    "id": None,
                    "error": {"code": -32700, "message": str(e)},
                }
                sys.stdout.write(json.dumps(err) + "\n")
                sys.stdout.flush()
    except KeyboardInterrupt:
        pass
    finally:
        shutdown_telemetry()
