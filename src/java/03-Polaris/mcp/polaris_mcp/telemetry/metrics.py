"""
Polaris MCP Server - OpenTelemetry Metrics Instruments
Provides zero-dependency OpenTelemetry Counter and Histogram implementations
for capturing tool invocations, execution latencies, and HTTP metrics.
"""

import time
import threading
from typing import Dict, Any, List, Optional, Tuple


class MetricPoint:
    def __init__(self, value: float, attributes: Dict[str, Any], timestamp_ns: int):
        self.value = value
        self.attributes = attributes
        self.timestamp_ns = timestamp_ns


class Counter:
    """Monotonically increasing cumulative counter."""

    def __init__(self, name: str, unit: str = "1", description: str = ""):
        self.name = name
        self.unit = unit
        self.description = description
        self._values: Dict[Tuple[Tuple[str, Any], ...], float] = {}
        self._lock = threading.Lock()

    def add(self, value: float = 1.0, attributes: Optional[Dict[str, Any]] = None) -> None:
        if value < 0:
            return
        attrs = attributes or {}
        key = tuple(sorted(attrs.items()))
        with self._lock:
            self._values[key] = self._values.get(key, 0.0) + value

    def collect(self) -> Dict[str, Any]:
        """Returns OTLP metric data representation."""
        data_points = []
        now_ns = time.time_ns()
        with self._lock:
            for key, val in self._values.items():
                attrs = dict(key)
                data_points.append({
                    "attributes": [
                        {"key": k, "value": {"stringValue": str(v)}}
                        for k, v in attrs.items()
                    ],
                    "timeUnixNano": str(now_ns),
                    "asDouble": float(val),
                })
        return {
            "name": self.name,
            "description": self.description,
            "unit": self.unit,
            "sum": {
                "dataPoints": data_points,
                "aggregationTemporality": 2,  # AGGREGATION_TEMPORALITY_CUMULATIVE
                "isMonotonic": True,
            },
        }


class Histogram:
    """Histogram instrument for tracking value distributions (e.g. durations)."""

    DEFAULT_BOUNDS = [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0]

    def __init__(
        self,
        name: str,
        unit: str = "s",
        description: str = "",
        explicit_bounds: Optional[List[float]] = None,
    ):
        self.name = name
        self.unit = unit
        self.description = description
        self.explicit_bounds = explicit_bounds or self.DEFAULT_BOUNDS
        self._data: Dict[Tuple[Tuple[str, Any], ...], Dict[str, Any]] = {}
        self._lock = threading.Lock()

    def record(self, value: float, attributes: Optional[Dict[str, Any]] = None) -> None:
        attrs = attributes or {}
        key = tuple(sorted(attrs.items()))
        with self._lock:
            if key not in self._data:
                self._data[key] = {
                    "count": 0,
                    "sum": 0.0,
                    "min": value,
                    "max": value,
                    "bucket_counts": [0] * (len(self.explicit_bounds) + 1),
                }
            entry = self._data[key]
            entry["count"] += 1
            entry["sum"] += value
            entry["min"] = min(entry["min"], value)
            entry["max"] = max(entry["max"], value)

            # Update bucket counts
            placed = False
            for i, bound in enumerate(self.explicit_bounds):
                if value <= bound:
                    entry["bucket_counts"][i] += 1
                    placed = True
                    break
            if not placed:
                entry["bucket_counts"][-1] += 1

    def collect(self) -> Dict[str, Any]:
        """Returns OTLP histogram metric representation."""
        data_points = []
        now_ns = time.time_ns()
        with self._lock:
            for key, entry in self._data.items():
                attrs = dict(key)
                data_points.append({
                    "attributes": [
                        {"key": k, "value": {"stringValue": str(v)}}
                        for k, v in attrs.items()
                    ],
                    "timeUnixNano": str(now_ns),
                    "count": str(entry["count"]),
                    "sum": entry["sum"],
                    "min": entry["min"],
                    "max": entry["max"],
                    "bucketCounts": [str(c) for c in entry["bucket_counts"]],
                    "explicitBounds": self.explicit_bounds,
                })
        return {
            "name": self.name,
            "description": self.description,
            "unit": self.unit,
            "histogram": {
                "dataPoints": data_points,
                "aggregationTemporality": 2,
            },
        }


class Meter:
    """Manages metric instruments."""

    def __init__(self, name: str = "polaris_mcp", version: str = "1.0.0"):
        self.name = name
        self.version = version
        self._instruments: List[Any] = []
        self._lock = threading.Lock()

    def create_counter(self, name: str, unit: str = "1", description: str = "") -> Counter:
        counter = Counter(name, unit, description)
        with self._lock:
            self._instruments.append(counter)
        return counter

    def create_histogram(
        self,
        name: str,
        unit: str = "s",
        description: str = "",
        explicit_bounds: Optional[List[float]] = None,
    ) -> Histogram:
        histogram = Histogram(name, unit, description, explicit_bounds)
        with self._lock:
            self._instruments.append(histogram)
        return histogram

    def collect_metrics(self) -> List[Dict[str, Any]]:
        metrics = []
        with self._lock:
            for inst in self._instruments:
                metrics.append(inst.collect())
        return metrics
