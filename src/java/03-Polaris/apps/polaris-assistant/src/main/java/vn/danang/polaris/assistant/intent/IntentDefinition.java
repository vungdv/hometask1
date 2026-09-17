package vn.danang.polaris.assistant.intent;

import java.util.ArrayList;
import java.util.List;

public class IntentDefinition {

    private String id;
    private String description;
    private List<String> examples = new ArrayList<>();
    private List<String> allowedTools = new ArrayList<>();
    private String requiredScope;
    private double confidenceThreshold = 0.80;
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

    public void setId(String id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public void setRequiredScope(String requiredScope) {
        this.requiredScope = requiredScope;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public boolean isMutating() {
        return mutating;
    }

    public void setMutating(boolean mutating) {
        this.mutating = mutating;
    }
}
