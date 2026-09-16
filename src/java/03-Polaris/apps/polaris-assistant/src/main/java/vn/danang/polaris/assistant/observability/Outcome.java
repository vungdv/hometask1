package vn.danang.polaris.assistant.observability;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Outcome(
        @JsonProperty("status") String status,
        @JsonProperty("detail") String detail,
        @JsonProperty("latency_ms") long latencyMs
) {}
