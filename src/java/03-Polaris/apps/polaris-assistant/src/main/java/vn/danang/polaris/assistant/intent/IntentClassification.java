package vn.danang.polaris.assistant.intent;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Value object representing an intent classification outcome.
 *
 * @param intentId unique identifier of the classified intent
 * @param confidence classification confidence score between 0.0 and 1.0
 * @param fallback whether this result came from a fail-safe default rather than an actual
 *                 classifier judgment (e.g. missing config, request error, malformed response)
 * @param fallbackReason short machine-readable reason code for the fallback, or {@code null} when
 *                        {@code fallback} is {@code false}
 */
public record IntentClassification(
        @JsonProperty("intent_id") String intentId,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("fallback") boolean fallback,
        @JsonProperty("fallback_reason") String fallbackReason
) {

    /**
     * Convenience constructor for a genuine (non-fallback) classification result.
     */
    public IntentClassification(String intentId, double confidence) {
        this(intentId, confidence, false, null);
    }

    /**
     * Builds a fallback classification, always with zero confidence, tagged with the reason it
     * occurred so callers (e.g. tracing) can surface it clearly instead of it looking like a
     * genuine low-confidence judgment.
     */
    public static IntentClassification fallback(String intentId, String reason) {
        return new IntentClassification(intentId, 0.0, true, reason);
    }
}
