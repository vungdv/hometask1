package vn.danang.polaris.assistant.intent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

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
}
