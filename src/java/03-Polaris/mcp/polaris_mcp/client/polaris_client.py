"""
Polaris MCP Server - HTTP Client Layer (RFC 9110 HTTP Semantics)
Handles communication with Polaris Spring Boot backend.
Injects W3C traceparent headers for distributed tracing and collects HTTP metrics.
"""

import json
import time
import urllib.parse
import urllib.request
import urllib.error
from typing import Optional, Dict, Any
from ..config import POLARIS_BASE_URL, SSL_CONTEXT
from ..auth import get_access_token
from ..telemetry import get_tracer, get_meter, get_logger, SpanKind, StatusCode

tracer = get_tracer()
meter = get_meter()
logger = get_logger("polaris_mcp.client")

http_requests_counter = meter.create_counter(
    "mcp_http_requests_total",
    unit="1",
    description="Total HTTP requests to Polaris backend",
)
http_duration_histogram = meter.create_histogram(
    "mcp_http_request_duration_seconds",
    unit="s",
    description="Latency distribution of HTTP requests to Polaris backend",
)


def http_get(path: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """
    Executes an authenticated HTTP GET request against Polaris API.
    Propagates W3C traceparent header and captures OpenTelemetry metrics.
    """
    url = f"{POLARIS_BASE_URL.rstrip('/')}{path}"
    if params:
        filtered_params = {k: str(v) for k, v in params.items() if v is not None}
        if filtered_params:
            url += f"?{urllib.parse.urlencode(filtered_params)}"

    try:
        token = get_access_token()
    except RuntimeError as auth_err:
        return {"error": str(auth_err)}

    start_time = time.time()
    with tracer.start_as_current_span(f"http.get {path}", kind=SpanKind.CLIENT) as span:
        traceparent = span.context.to_traceparent()
        span.set_attribute("http.method", "GET")
        span.set_attribute("http.url", url)
        span.set_attribute("http.target", path)

        req = urllib.request.Request(
            url,
            headers={
                "Accept": "application/json",
                "User-Agent": "Polaris-MCP-Agent/1.0",
                "traceparent": traceparent,
                "Authorization": f"Bearer {token}",
            },
        )

        try:
            with urllib.request.urlopen(req, context=SSL_CONTEXT, timeout=10) as response:
                duration = time.time() - start_time
                status_code = response.status
                span.set_attribute("http.status_code", status_code)
                span.set_status(StatusCode.OK)

                http_requests_counter.add(1, {"method": "GET", "status_code": str(status_code)})
                http_duration_histogram.record(duration, {"path": path})

                data = response.read().decode("utf-8")
                return json.loads(data)

        except urllib.error.HTTPError as e:
            duration = time.time() - start_time
            span.set_attribute("http.status_code", e.code)
            span.set_status(StatusCode.ERROR, f"HTTP {e.code}: {e.reason}")
            span.record_exception(e)

            http_requests_counter.add(1, {"method": "GET", "status_code": str(e.code)})
            http_duration_histogram.record(duration, {"path": path})

            error_body = e.read().decode("utf-8")
            try:
                parsed = json.loads(error_body)
                # RFC 7807 Problem Details support
                error_msg = parsed.get("detail", parsed.get("message", f"HTTP {e.code}: {e.reason}"))
                return {"error": error_msg}
            except Exception:
                return {"error": f"HTTP {e.code}: {e.reason}"}

        except Exception as e:
            duration = time.time() - start_time
            span.set_status(StatusCode.ERROR, str(e))
            span.record_exception(e)
            http_requests_counter.add(1, {"method": "GET", "status_code": "error"})
            http_duration_histogram.record(duration, {"path": path})
            logger.error("HTTP GET request failed", url=url, error=str(e))
            return {"error": f"Failed to connect to Polaris at {url}: {str(e)}"}
