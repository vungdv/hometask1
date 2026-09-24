package vn.danang.polaris.assistant.intent;

import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

public class IntentDefinition {

    @Setter
    private String id;
    @Setter
    private String description;
    private List<String> examples = new ArrayList<>();
    private List<String> allowedTools = new ArrayList<>();
    @Setter
    private String requiredScope;
    @Setter
    private double confidenceThreshold = 0.80;
    @Setter
    private boolean mutating = false;

    public IntentDefinition() {
    }

    public IntentDefinition(
            String id,
            String description,
            List<String> examples,
            List<String> allowedTools,
            String requiredScope,
            double confidenceThreshold,
            boolean mutating) {
        this.id = id;
        this.description = description;
        this.examples = examples != null ? new ArrayList<>(examples) : new ArrayList<>();
        this.allowedTools = allowedTools != null ? new ArrayList<>(allowedTools) : new ArrayList<>();
        this.requiredScope = requiredScope;
        this.confidenceThreshold = confidenceThreshold;
        this.mutating = mutating;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getExamples() {
        return examples;
    }

    public void setExamples(List<String> examples) {
        this.examples = examples != null ? new ArrayList<>(examples) : new ArrayList<>();
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools != null ? new ArrayList<>(allowedTools) : new ArrayList<>();
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
