package vn.danang.polaris.assistant.intent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plain immutable record representing an intent definition in the assistant taxonomy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IntentDefinition(
        String id,
        String description,
        List<String> examples,
        List<String> allowedTools,
        String requiredScope,
        double confidenceThreshold,
        boolean mutating
) {

    public IntentDefinition {
        examples = examples != null ? List.copyOf(examples) : List.of();
        allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
        if (confidenceThreshold <= 0.0) {
            confidenceThreshold = 0.80;
        }
    }

    public IntentDefinition(String id, String description, List<String> examples) {
        this(id, description, examples, List.of(), null, 0.80, false);
    }

    /**
     * Convenience deserializer for a single IntentDefinition JSON string.
     */
    public static IntentDefinition fromJson(String json) {
        try {
            return new ObjectMapper().readValue(json, IntentDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse IntentDefinition from JSON", e);
        }
    }

    // Compatibility getter aliases matching standard JavaBean conventions
    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getExamples() {
        return examples;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public boolean isMutating() {
        return mutating;
    }
}
