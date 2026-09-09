"""
Unit and integration tests for Polaris MCP Server & Telemetry Subsystem.
Uses standard library unittest (zero external dependencies required).
"""

import io
import json
import os
import sys
import unittest
from unittest.mock import patch, MagicMock

# Ensure mcp directory is on sys.path
_MCP_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _MCP_DIR not in sys.path:
    sys.path.insert(0, _MCP_DIR)

from polaris_mcp.telemetry.context import (
    SpanContext,
    generate_trace_id,
    generate_span_id,
    get_current_span,
)
from polaris_mcp.telemetry.tracer import Tracer, SpanKind, StatusCode
from polaris_mcp.telemetry.metrics import Meter, Counter, Histogram
from polaris_mcp.telemetry.otlp_exporter import _span_to_otlp_dict
from polaris_mcp.auth.pkce import generate_pkce_pair, decode_jwt_payload
from polaris_mcp.auth.token_cache import save_cached_tokens, load_cached_tokens, clear_cached_tokens
from polaris_mcp.server.jsonrpc import handle_json_rpc
from polaris_mcp.tools import TOOLS, execute_tool


class TestW3CTraceContext(unittest.TestCase):
    def test_trace_and_span_id_lengths(self):
        trace_id = generate_trace_id()
        span_id = generate_span_id()
        self.assertEqual(len(trace_id), 32)
        self.assertEqual(len(span_id), 16)
        int(trace_id, 16)  # Valid hex
        int(span_id, 16)   # Valid hex

    def test_traceparent_serialization_and_parsing(self):
        ctx = SpanContext(
            trace_id="4bf92f3577b34da6a3ce929d0e0e4736",
            span_id="00f067aa0ba902b7",
            trace_flags="01",
        )
        header = ctx.to_traceparent()
        self.assertEqual(header, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")

        parsed = SpanContext.from_traceparent(header)
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.trace_id, ctx.trace_id)
        self.assertEqual(parsed.span_id, ctx.span_id)
        self.assertEqual(parsed.trace_flags, "01")
        self.assertTrue(parsed.is_remote)

    def test_invalid_traceparent(self):
        self.assertIsNone(SpanContext.from_traceparent("invalid-header"))
        self.assertIsNone(SpanContext.from_traceparent("00-00000000000000000000000000000000-00f067aa0ba902b7-01"))


class TestTracerAndSpans(unittest.TestCase):
    def test_span_hierarchy_and_context(self):
        ended_spans = []
        tracer = Tracer(name="test_tracer", on_span_end=lambda s: ended_spans.append(s))

        with tracer.start_as_current_span("parent_span") as parent:
            parent.set_attribute("env", "test")
            self.assertEqual(get_current_span(), parent)

            with tracer.start_as_current_span("child_span") as child:
                self.assertEqual(get_current_span(), child)
                self.assertEqual(child.context.trace_id, parent.context.trace_id)
                self.assertEqual(child.parent_span_id, parent.context.span_id)
                child.set_status(StatusCode.OK)

            self.assertEqual(get_current_span(), parent)

        self.assertEqual(len(ended_spans), 2)
        child_span, parent_span = ended_spans
        self.assertEqual(child_span.name, "child_span")
        self.assertEqual(parent_span.name, "parent_span")
        self.assertEqual(child_span.context.trace_id, parent_span.context.trace_id)

    def test_exception_recording(self):
        ended_spans = []
        tracer = Tracer(name="test_tracer", on_span_end=lambda s: ended_spans.append(s))

        try:
            with tracer.start_as_current_span("error_span") as span:
                raise ValueError("Simulated failure")
        except ValueError:
            pass

        self.assertEqual(len(ended_spans), 1)
        span = ended_spans[0]
        self.assertEqual(span.status_code, StatusCode.ERROR)
        self.assertEqual(len(span.events), 1)
        self.assertEqual(span.events[0]["name"], "exception")
        self.assertIn("Simulated failure", span.events[0]["attributes"]["exception.message"])

    def test_otlp_serialization(self):
        tracer = Tracer(name="test_tracer")
        span = tracer.start_span("http.get", kind=SpanKind.CLIENT, attributes={"http.status": 200})
        span.end()

        otlp_dict = _span_to_otlp_dict(span)
        self.assertEqual(otlp_dict["name"], "http.get")
        self.assertEqual(otlp_dict["kind"], SpanKind.CLIENT)
        self.assertEqual(otlp_dict["traceId"], span.context.trace_id)
        self.assertTrue(any(attr["key"] == "http.status" for attr in otlp_dict["attributes"]))


class TestMetrics(unittest.TestCase):
    def test_counter_and_histogram(self):
        meter = Meter(name="test_meter")
        counter = meter.create_counter("test_counter")
        histogram = meter.create_histogram("test_latency")

        counter.add(1, {"tool": "search"})
        counter.add(2, {"tool": "search"})
        histogram.record(0.042, {"endpoint": "/products"})

        metrics = meter.collect_metrics()
        self.assertEqual(len(metrics), 2)

        c_metric = next(m for m in metrics if m["name"] == "test_counter")
        self.assertEqual(c_metric["sum"]["dataPoints"][0]["asDouble"], 3.0)

        h_metric = next(m for m in metrics if m["name"] == "test_latency")
        self.assertEqual(h_metric["histogram"]["dataPoints"][0]["count"], "1")


class TestOAuthAndPKCE(unittest.TestCase):
    def test_pkce_generation(self):
        verifier, challenge = generate_pkce_pair()
        self.assertTrue(43 <= len(verifier) <= 128)
        self.assertTrue(len(challenge) > 0)
        self.assertNotIn("=", challenge)  # Base64URL without padding

    def test_jwt_payload_decoding(self):
        # Sample unencrypted JWT payload
        header = "eyJhbGciOiJub25lIn0"
        payload = "eyJzdWIiOiIxMjM0NSIsInByZWZlcnJlZF91c2VybmFtZSI6ImFsaWNlIn0"
        token = f"{header}.{payload}."
        decoded = decode_jwt_payload(token)
        self.assertEqual(decoded.get("preferred_username"), "alice")
        self.assertEqual(decoded.get("sub"), "12345")


class TestJSONRPCProtocol(unittest.TestCase):
    def test_initialize(self):
        req = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
        res = handle_json_rpc(req)
        self.assertEqual(res["jsonrpc"], "2.0")
        self.assertEqual(res["id"], 1)
        self.assertEqual(res["result"]["serverInfo"]["name"], "polaris-product-search-mcp")

    def test_tools_list(self):
        req = {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
        res = handle_json_rpc(req)
        self.assertEqual(res["id"], 2)
        tool_names = [t["name"] for t in res["result"]["tools"]]
        self.assertIn("search_available_products", tool_names)
        self.assertIn("get_product_by_sku", tool_names)

    def test_unknown_tool(self):
        req = {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {"name": "non_existent_tool", "arguments": {}},
        }
        res = handle_json_rpc(req)
        self.assertEqual(res["error"]["code"], -32601)

    def test_unsupported_method(self):
        req = {"jsonrpc": "2.0", "id": 4, "method": "unknown_rpc_method", "params": {}}
        res = handle_json_rpc(req)
        self.assertEqual(res["error"]["code"], -32601)

    def test_search_tool_schema_has_pagination_and_sort(self):
        search_tool = next(t for t in TOOLS if t["name"] == "search_available_products")
        props = search_tool["inputSchema"]["properties"]
        self.assertIn("page", props)
        self.assertIn("size", props)
        self.assertIn("sort", props)
        self.assertEqual(props["page"]["type"], "integer")
        self.assertEqual(props["size"]["type"], "integer")
        self.assertEqual(props["sort"]["type"], "string")

    @patch("polaris_mcp.tools.product_search.http_get")
    def test_search_tool_with_pagination_and_sort(self, mock_http_get):
        mock_http_get.return_value = {
            "content": [
                {
                    "sku": "NG-EARBUD-01",
                    "name": "Nova Wireless Earbuds",
                    "category": "Audio",
                    "price": 49.90,
                    "stockQuantity": 120,
                    "isAvailable": True,
                }
            ],
            "totalElements": 1,
        }
        res = execute_tool(
            "search_available_products",
            {"query": "wireless", "page": 0, "size": 10, "sort": "price,asc"},
        )
        self.assertIn("Found 1 product(s):", res)
        self.assertIn("[NG-EARBUD-01]", res)
        mock_http_get.assert_called_once_with(
            "/api/v1/products",
            {
                "query": "wireless",
                "category": None,
                "minPrice": None,
                "maxPrice": None,
                "available": True,
                "page": 0,
                "size": 10,
                "sort": "price,asc",
            },
        )

    @patch("polaris_mcp.tools.product_search.http_get")
    def test_search_tool_handles_problem_detail_error(self, mock_http_get):
        mock_http_get.return_value = {
            "error": "Invalid sort property 'string'. Allowed sort properties are: [id, sku, name, category, price, stockQuantity, stockQty, active, createdAt]. Format: property(,asc|desc)."
        }
        res = execute_tool(
            "search_available_products",
            {"sort": "string"},
        )
        self.assertIn("Error querying products: Invalid sort property 'string'", res)


if __name__ == "__main__":
    unittest.main()

