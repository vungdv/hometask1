package vn.danang.polaris.assistant.policy;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PolicyDecision(
        @JsonProperty("allowed") boolean allowed,
        @JsonProperty("reason") String reason
) {
    public static PolicyDecision allow() {
        return new PolicyDecision(true, null);
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, reason);
    }
}
