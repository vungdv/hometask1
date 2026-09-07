"""
Polaris MCP Server - JSON-RPC 2.0 Handler
Processes Model Context Protocol (MCP) JSON-RPC requests with OpenTelemetry tracing.
"""

from typing import Optional, Dict, Any
from ..tools import TOOLS, execute_tool
from ..telemetry import get_tracer, get_logger, SpanKind, StatusCode

tracer = get_tracer()
logger = get_logger("polaris_mcp.server.jsonrpc")


def handle_json_rpc(request: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Handles an incoming JSON-RPC 2.0 request or notification."""
    method = request.get("method")
    req_id = request.get("id")

    with tracer.start_as_current_span(f"mcp.rpc {method or 'unknown'}", kind=SpanKind.SERVER) as span:
        span.set_attribute("rpc.system", "mcp")
        span.set_attribute("rpc.method", method or "")
        if req_id is not None:
            span.set_attribute("rpc.id", str(req_id))

        if method == "initialize":
            span.set_status(StatusCode.OK)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {
                        "name": "polaris-product-search-mcp",
                        "version": "1.0.0",
                    },
                },
            }

        elif method == "tools/list":
            span.set_status(StatusCode.OK)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"tools": TOOLS},
            }

        elif method == "tools/call":
            params = request.get("params", {})
            tool_name = params.get("name")
            args = params.get("arguments", {})
            span.set_attribute("mcp.tool_name", tool_name or "")

            try:
                text_result = execute_tool(tool_name, args)
                span.set_status(StatusCode.OK)
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [{"type": "text", "text": text_result}]
                    },
                }
            except ValueError as ve:
                span.set_status(StatusCode.ERROR, str(ve))
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": str(ve)},
                }
            except Exception as ex:
                span.set_status(StatusCode.ERROR, str(ex))
                span.record_exception(ex)
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "isError": True,
                        "content": [{"type": "text", "text": f"Execution error: {str(ex)}"}],
                    },
                }

        elif method == "notifications/initialized":
            span.set_status(StatusCode.OK)
            return None

        else:
            span.set_status(StatusCode.ERROR, f"Method not supported: {method}")
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32601, "message": f"Method not supported: {method}"},
            }
