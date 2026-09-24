package vn.danang.polaris.assistant.intent;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Value object representing an intent classification outcome.
 *
 * @param intentId unique identifier of the classified intent
 * @param confidence classification confidence score between 0.0 and 1.0
 */
public record IntentClassification(
        @JsonProperty("intent_id") String intentId,
        @JsonProperty("confidence") double confidence
) {
}
