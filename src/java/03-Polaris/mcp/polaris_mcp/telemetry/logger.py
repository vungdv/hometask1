"""
Polaris MCP Server - Structured JSON Logger
Writes structured logs exclusively to stderr to preserve stdio JSON-RPC on stdout.
Automatically correlates active W3C trace_id and span_id into log records.
"""

import sys
import json
import time
from typing import Optional, Any
from .context import get_current_span
from ..config import OTEL_LOG_LEVEL

LEVEL_VALUES = {
    "DEBUG": 10,
    "INFO": 20,
    "WARNING": 30,
    "WARN": 30,
    "ERROR": 40,
}


class StructuredLogger:
    def __init__(self, name: str = "polaris_mcp"):
        self.name = name

    def _should_log(self, level: str) -> bool:
        threshold = LEVEL_VALUES.get(OTEL_LOG_LEVEL, 20)
        return LEVEL_VALUES.get(level, 20) >= threshold

    def _log(self, level: str, message: str, **kwargs) -> None:
        if not self._should_log(level):
            return

        record = {
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "level": level,
            "logger": self.name,
            "message": message,
        }

        # Correlate active trace and span if present
        span = get_current_span()
        if span and hasattr(span, "context"):
            record["trace_id"] = span.context.trace_id
            record["span_id"] = span.context.span_id

        if kwargs:
            for k, v in kwargs.items():
                if v is not None:
                    record[k] = v

        try:
            sys.stderr.write(json.dumps(record) + "\n")
            sys.stderr.flush()
        except Exception:
            pass

    def debug(self, message: str, **kwargs) -> None:
        self._log("DEBUG", message, **kwargs)

    def info(self, message: str, **kwargs) -> None:
        self._log("INFO", message, **kwargs)

    def warning(self, message: str, **kwargs) -> None:
        self._log("WARNING", message, **kwargs)

    def error(self, message: str, **kwargs) -> None:
        self._log("ERROR", message, **kwargs)


def get_logger(name: str = "polaris_mcp") -> StructuredLogger:
    return StructuredLogger(name)
