package vn.danang.polaris.assistant.observability;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EvaluatedAlternative(
        @JsonProperty("action") String action,
        @JsonProperty("reason_considered") String reasonConsidered,
        @JsonProperty("selected") boolean selected,
        @JsonProperty("reason_rejected") String reasonRejected
) {}
