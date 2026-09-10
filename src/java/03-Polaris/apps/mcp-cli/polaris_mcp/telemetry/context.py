"""
Polaris MCP Server - W3C Trace Context & Span Context
Implements W3C Trace Context specification (RFC / W3C Recommendation)
for distributed trace propagation across MCP and Polaris services.
"""

import contextvars
import random
import re
from dataclasses import dataclass
from typing import Optional

TRACEPARENT_REGEX = re.compile(
    r"^00-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$"
)


def generate_trace_id() -> str:
    """Generates a 128-bit (32 hex chars) trace ID."""
    return f"{random.getrandbits(128):032x}"


def generate_span_id() -> str:
    """Generates a 64-bit (16 hex chars) span ID."""
    return f"{random.getrandbits(64):016x}"


@dataclass
class SpanContext:
    trace_id: str
    span_id: str
    trace_flags: str = "01"
    is_remote: bool = False

    def to_traceparent(self) -> str:
        """Formats W3C traceparent header: 00-{trace_id}-{span_id}-{trace_flags}"""
        return f"00-{self.trace_id}-{self.span_id}-{self.trace_flags}"

    @classmethod
    def from_traceparent(cls, header: str) -> Optional["SpanContext"]:
        """Parses a W3C traceparent header."""
        match = TRACEPARENT_REGEX.match(header.strip())
        if not match:
            return None
        trace_id, span_id, trace_flags = match.groups()
        # Invalid trace ID or span ID (all zeroes)
        if trace_id == "0" * 32 or span_id == "0" * 16:
            return None
        return cls(
            trace_id=trace_id.lower(),
            span_id=span_id.lower(),
            trace_flags=trace_flags.lower(),
            is_remote=True
        )


# Thread-safe and async-safe context variable for current active span
_CURRENT_SPAN: contextvars.ContextVar[Optional[object]] = contextvars.ContextVar(
    "current_span", default=None
)


def get_current_span() -> Optional[object]:
    """Returns the currently active span in the execution context."""
    return _CURRENT_SPAN.get()


def set_current_span(span: Optional[object]):
    """Sets the currently active span in the execution context and returns the token."""
    return _CURRENT_SPAN.set(span)


def reset_current_span(token) -> None:
    """Resets the context variable to its previous state using the token."""
    _CURRENT_SPAN.reset(token)
