"""
Polaris MCP Server - OTLP/HTTP JSON Telemetry Exporter
Transmits distributed traces and metrics to OpenTelemetry Collector
using standard OTLP/HTTP JSON format (RFC / OTel specification).
Operates asynchronously in a background daemon thread to ensure zero MCP latency.
"""

import json
import queue
import threading
import time
import urllib.request
import urllib.error
from typing import List, Dict, Any, Optional
from ..config import (
    OTEL_SERVICE_NAME,
    OTEL_SERVICE_VERSION,
    OTEL_EXPORTER_OTLP_ENDPOINT,
    OTEL_SDK_DISABLED,
)
from .tracer import Span


def _convert_value_to_any_value(val: Any) -> Dict[str, Any]:
    """Converts a Python value to OTLP AnyValue JSON schema."""
    if isinstance(val, bool):
        return {"boolValue": val}
    elif isinstance(val, int):
        return {"intValue": str(val)}
    elif isinstance(val, float):
        return {"doubleValue": val}
    elif isinstance(val, list):
        return {
            "arrayValue": {
                "values": [_convert_value_to_any_value(item) for item in val]
            }
        }
    else:
        return {"stringValue": str(val)}


def _span_to_otlp_dict(span: Span) -> Dict[str, Any]:
    """Converts a Span object to OTLP JSON span format."""
    span_dict: Dict[str, Any] = {
        "traceId": span.context.trace_id,
        "spanId": span.context.span_id,
        "name": span.name,
        "kind": span.kind,
        "startTimeUnixNano": str(span.start_time_ns),
        "endTimeUnixNano": str(span.end_time_ns or span.start_time_ns),
        "status": {"code": span.status_code},
        "attributes": [
            {"key": k, "value": _convert_value_to_any_value(v)}
            for k, v in span.attributes.items()
        ],
    }
    if span.parent_span_id:
        span_dict["parentSpanId"] = span.parent_span_id
    if span.status_message:
        span_dict["status"]["message"] = span.status_message

    if span.events:
        span_dict["events"] = [
            {
                "timeUnixNano": str(evt.get("time_unix_nano", span.start_time_ns)),
                "name": evt.get("name", ""),
                "attributes": [
                    {"key": k, "value": _convert_value_to_any_value(v)}
                    for k, v in evt.get("attributes", {}).items()
                ],
            }
            for evt in span.events
        ]

    return span_dict


class OTLPExporter:
    """Background asynchronous exporter for OTLP traces and metrics."""

    def __init__(
        self,
        endpoint: str = OTEL_EXPORTER_OTLP_ENDPOINT,
        service_name: str = OTEL_SERVICE_NAME,
        batch_size: int = 64,
        flush_interval_sec: float = 1.0,
    ):
        self.endpoint = endpoint.rstrip("/")
        self.service_name = service_name
        self.batch_size = batch_size
        self.flush_interval_sec = flush_interval_sec
        self.disabled = OTEL_SDK_DISABLED

        self._span_queue: queue.Queue = queue.Queue(maxsize=4096)
        self._running = False
        self._worker_thread: Optional[threading.Thread] = None
        self._meter = None

        if not self.disabled:
            self._start_worker()

    def set_meter(self, meter) -> None:
        self._meter = meter

    def _start_worker(self) -> None:
        self._running = True
        self._worker_thread = threading.Thread(
            target=self._run_loop, name="polaris-otlp-exporter", daemon=True
        )
        self._worker_thread.start()

    def enqueue_span(self, span: Span) -> None:
        if self.disabled or not self._running:
            return
        try:
            self._span_queue.put_nowait(span)
        except queue.Full:
            # Drop oldest if queue is full to prevent memory growth
            pass

    def _run_loop(self) -> None:
        last_metric_flush = time.time()
        while self._running:
            spans_to_export: List[Span] = []
            deadline = time.time() + self.flush_interval_sec

            while time.time() < deadline and len(spans_to_export) < self.batch_size:
                timeout = max(0.05, deadline - time.time())
                try:
                    span = self._span_queue.get(timeout=timeout)
                    spans_to_export.append(span)
                except queue.Empty:
                    break

            if spans_to_export:
                self._export_spans(spans_to_export)

            # Flush metrics periodically
            if time.time() - last_metric_flush >= 5.0:
                self._export_metrics()
                last_metric_flush = time.time()

    def _export_spans(self, spans: List[Span]) -> None:
        url = f"{self.endpoint}/v1/traces"
        payload = {
            "resourceSpans": [
                {
                    "resource": {
                        "attributes": [
                            {
                                "key": "service.name",
                                "value": {"stringValue": self.service_name},
                            },
                            {
                                "key": "service.version",
                                "value": {"stringValue": OTEL_SERVICE_VERSION},
                            },
                        ]
                    },
                    "scopeSpans": [
                        {
                            "scope": {
                                "name": "polaris_mcp",
                                "version": "1.0.0",
                            },
                            "spans": [_span_to_otlp_dict(s) for s in spans],
                        }
                    ],
                }
            ]
        }
        self._send_otlp_http(url, payload)

    def _export_metrics(self) -> None:
        if not self._meter:
            return
        metrics_data = self._meter.collect_metrics()
        if not metrics_data:
            return

        url = f"{self.endpoint}/v1/metrics"
        payload = {
            "resourceMetrics": [
                {
                    "resource": {
                        "attributes": [
                            {
                                "key": "service.name",
                                "value": {"stringValue": self.service_name},
                            }
                        ]
                    },
                    "scopeMetrics": [
                        {
                            "scope": {
                                "name": "polaris_mcp",
                                "version": "1.0.0",
                            },
                            "metrics": metrics_data,
                        }
                    ],
                }
            ]
        }
        self._send_otlp_http(url, payload)

    def _send_otlp_http(self, url: str, payload: Dict[str, Any]) -> None:
        try:
            data = json.dumps(payload).encode("utf-8")
            req = urllib.request.Request(
                url,
                data=data,
                headers={
                    "Content-Type": "application/json",
                    "User-Agent": f"polaris-mcp-otlp-exporter/{OTEL_SERVICE_VERSION}",
                },
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=2.0) as resp:
                pass
        except Exception:
            # Silently tolerate collector unavailability; never block or crash application
            pass

    def shutdown(self) -> None:
        if not self._running:
            return
        self._running = False
        # Drain remaining spans
        spans: List[Span] = []
        while not self._span_queue.empty():
            try:
                spans.append(self._span_queue.get_nowait())
            except queue.Empty:
                break
        if spans:
            self._export_spans(spans)
        self._export_metrics()
