"""
Polaris MCP Server - OpenTelemetry Tracer & Span Implementation
Provides zero-dependency OpenTelemetry Span and Tracer implementations
supporting context managers, W3C trace propagation, and lifecycle callbacks.
"""

import time
import traceback
from typing import Optional, Dict, Any, List, Callable
from .context import (
    SpanContext,
    generate_trace_id,
    generate_span_id,
    get_current_span,
    set_current_span,
    reset_current_span,
)


class SpanKind:
    INTERNAL = 1
    SERVER = 2
    CLIENT = 3
    PRODUCER = 4
    CONSUMER = 5


class StatusCode:
    UNSET = 0
    OK = 1
    ERROR = 2


class Span:
    """Represents an OpenTelemetry Span."""

    def __init__(
        self,
        name: str,
        context: SpanContext,
        parent_span_id: Optional[str] = None,
        kind: int = SpanKind.INTERNAL,
        attributes: Optional[Dict[str, Any]] = None,
        on_end_callback: Optional[Callable[["Span"], None]] = None,
    ):
        self.name = name
        self.context = context
        self.parent_span_id = parent_span_id
        self.kind = kind
        self.attributes: Dict[str, Any] = attributes.copy() if attributes else {}
        self.start_time_ns: int = time.time_ns()
        self.end_time_ns: Optional[int] = None
        self.status_code: int = StatusCode.UNSET
        self.status_message: Optional[str] = None
        self.events: List[Dict[str, Any]] = []
        self._on_end_callback = on_end_callback
        self._ended: bool = False
        self._context_token = None

    def set_attribute(self, key: str, value: Any) -> "Span":
        if not self._ended and value is not None:
            self.attributes[key] = value
        return self

    def set_attributes(self, attributes: Dict[str, Any]) -> "Span":
        if not self._ended and attributes:
            for k, v in attributes.items():
                if v is not None:
                    self.attributes[k] = v
        return self

    def set_status(self, code: int, message: Optional[str] = None) -> "Span":
        if not self._ended:
            self.status_code = code
            self.status_message = message
        return self

    def record_exception(self, exception: BaseException) -> "Span":
        if not self._ended:
            self.set_status(StatusCode.ERROR, str(exception))
            self.events.append({
                "time_unix_nano": time.time_ns(),
                "name": "exception",
                "attributes": {
                    "exception.type": type(exception).__name__,
                    "exception.message": str(exception),
                    "exception.stacktrace": "".join(
                        traceback.format_exception(
                            type(exception), exception, exception.__traceback__
                        )
                    ),
                },
            })
        return self

    def add_event(self, name: str, attributes: Optional[Dict[str, Any]] = None) -> "Span":
        if not self._ended:
            self.events.append({
                "time_unix_nano": time.time_ns(),
                "name": name,
                "attributes": attributes or {},
            })
        return self

    def end(self) -> None:
        if self._ended:
            return
        self.end_time_ns = time.time_ns()
        self._ended = True
        if self._on_end_callback:
            try:
                self._on_end_callback(self)
            except Exception:
                pass

    def __enter__(self) -> "Span":
        self._context_token = set_current_span(self)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        try:
            if exc_val is not None:
                self.record_exception(exc_val)
            elif self.status_code == StatusCode.UNSET:
                self.set_status(StatusCode.OK)
            self.end()
        finally:
            if self._context_token:
                reset_current_span(self._context_token)
                self._context_token = None


class Tracer:
    """Creates and manages Spans with OpenTelemetry context propagation."""

    def __init__(
        self,
        name: str = "polaris_mcp",
        version: str = "1.0.0",
        on_span_end: Optional[Callable[[Span], None]] = None,
    ):
        self.name = name
        self.version = version
        self._on_span_end = on_span_end

    def start_span(
        self,
        name: str,
        parent: Optional[SpanContext] = None,
        kind: int = SpanKind.INTERNAL,
        attributes: Optional[Dict[str, Any]] = None,
    ) -> Span:
        """Starts a new span as a child of parent or current active span."""
        if parent is None:
            active_span = get_current_span()
            if active_span and hasattr(active_span, "context"):
                parent = active_span.context

        if parent:
            trace_id = parent.trace_id
            parent_span_id = parent.span_id
            trace_flags = parent.trace_flags
        else:
            trace_id = generate_trace_id()
            parent_span_id = None
            trace_flags = "01"

        span_id = generate_span_id()
        context = SpanContext(
            trace_id=trace_id,
            span_id=span_id,
            trace_flags=trace_flags,
            is_remote=False,
        )

        return Span(
            name=name,
            context=context,
            parent_span_id=parent_span_id,
            kind=kind,
            attributes=attributes,
            on_end_callback=self._on_span_end,
        )

    def start_as_current_span(
        self,
        name: str,
        parent: Optional[SpanContext] = None,
        kind: int = SpanKind.INTERNAL,
        attributes: Optional[Dict[str, Any]] = None,
    ) -> Span:
        """Context manager creating a new span and making it the active context."""
        return self.start_span(name, parent=parent, kind=kind, attributes=attributes)
