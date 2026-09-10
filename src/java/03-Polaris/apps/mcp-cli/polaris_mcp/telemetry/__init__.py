"""
Polaris MCP Server - Telemetry Package
Unified facade for OpenTelemetry tracing, metrics, and structured logging.
"""

from .context import SpanContext, get_current_span, set_current_span
from .tracer import Tracer, Span, SpanKind, StatusCode
from .metrics import Meter, Counter, Histogram
from .logger import StructuredLogger, get_logger
from .otlp_exporter import OTLPExporter
from ..config import OTEL_SERVICE_NAME

# Singleton instances for global instrumentation
_exporter = OTLPExporter(service_name=OTEL_SERVICE_NAME)
_tracer = Tracer(name=OTEL_SERVICE_NAME, on_span_end=_exporter.enqueue_span)
_meter = Meter(name=OTEL_SERVICE_NAME)
_exporter.set_meter(_meter)


def get_tracer() -> Tracer:
    """Returns the global OpenTelemetry tracer instance."""
    return _tracer


def get_meter() -> Meter:
    """Returns the global OpenTelemetry meter instance."""
    return _meter


def shutdown_telemetry() -> None:
    """Flushes remaining spans/metrics and halts telemetry worker."""
    _exporter.shutdown()


__all__ = [
    "get_tracer",
    "get_meter",
    "get_logger",
    "shutdown_telemetry",
    "Span",
    "SpanContext",
    "SpanKind",
    "StatusCode",
    "Tracer",
    "Meter",
    "Counter",
    "Histogram",
    "StructuredLogger",
]
