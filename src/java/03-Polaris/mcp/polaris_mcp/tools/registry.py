"""
Polaris MCP Server - Tool Registry & Execution Dispatcher
Maintains MCP tool schemas and executes tools with OpenTelemetry telemetry.
"""

import time
from typing import List, Dict, Any
from .product_search import tool_search_available_products
from .product_details import tool_get_product_by_sku
from ..telemetry import get_tracer, get_meter, get_logger, StatusCode

tracer = get_tracer()
meter = get_meter()
logger = get_logger("polaris_mcp.tools")

tool_calls_counter = meter.create_counter(
    "mcp_tool_calls_total",
    unit="1",
    description="Total MCP tool execution calls",
)
tool_duration_histogram = meter.create_histogram(
    "mcp_tool_duration_seconds",
    unit="s",
    description="Duration of MCP tool executions",
)

TOOLS: List[Dict[str, Any]] = [
    {
        "name": "search_available_products",
        "description": (
            "Search the product catalog for available products matching keywords, categories, "
            "or price ranges. Automatically filters for in-stock items by default."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search term matching product name, SKU, or description (e.g., 'wireless', 'charger', 'watch')",
                },
                "category": {
                    "type": "string",
                    "description": "Product category: 'Audio', 'Wearables', 'Accessories'",
                },
                "min_price": {
                    "type": "number",
                    "description": "Minimum unit price in USD",
                },
                "max_price": {
                    "type": "number",
                    "description": "Maximum unit price in USD",
                },
                "available_only": {
                    "type": "boolean",
                    "description": "True to only return items with stockQty > 0 (default: true)",
                    "default": True,
                },
            },
        },
    },
    {
        "name": "get_product_by_sku",
        "description": "Retrieve comprehensive details and live inventory status for a specific product by its SKU code.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "sku": {
                    "type": "string",
                    "description": "The unique product SKU code (e.g., 'NG-EARBUD-01', 'NG-WATCH-01')",
                },
            },
            "required": ["sku"],
        },
    },
]


def execute_tool(name: str, args: Dict[str, Any]) -> str:
    """Executes a registered tool with full OpenTelemetry tracing and metrics."""
    start_time = time.time()
    with tracer.start_as_current_span(f"mcp.tool_call {name}") as span:
        span.set_attribute("mcp.tool_name", name)
        for k, v in args.items():
            if isinstance(v, (str, int, float, bool)):
                span.set_attribute(f"mcp.tool_arg.{k}", v)

        try:
            if name == "search_available_products":
                res = tool_search_available_products(
                    query=args.get("query"),
                    category=args.get("category"),
                    min_price=args.get("min_price"),
                    max_price=args.get("max_price"),
                    available_only=args.get("available_only", True),
                )
            elif name == "get_product_by_sku":
                res = tool_get_product_by_sku(args.get("sku", ""))
            else:
                raise ValueError(f"Unknown tool: {name}")

            duration = time.time() - start_time
            span.set_status(StatusCode.OK)
            tool_calls_counter.add(1, {"tool": name, "status": "success"})
            tool_duration_histogram.record(duration, {"tool": name})

            logger.info("Tool executed successfully", tool=name, duration_sec=round(duration, 4))
            return res

        except Exception as ex:
            duration = time.time() - start_time
            span.set_status(StatusCode.ERROR, str(ex))
            span.record_exception(ex)
            tool_calls_counter.add(1, {"tool": name, "status": "error"})
            tool_duration_histogram.record(duration, {"tool": name})

            logger.error("Tool execution error", tool=name, error=str(ex), duration_sec=round(duration, 4))
            raise
