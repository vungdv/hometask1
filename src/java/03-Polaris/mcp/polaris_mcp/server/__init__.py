"""
Polaris MCP Server - Server Package
"""

from .jsonrpc import handle_json_rpc
from .stdio import run_stdio

__all__ = ["handle_json_rpc", "run_stdio"]
