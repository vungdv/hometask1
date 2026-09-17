package vn.danang.polaris.assistant.intent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.Tool;

@Component
public class IntentToolRegistry {

    private final Map<String, IntentDefinition> intentMap = new ConcurrentHashMap<>();
    private final Map<String, String> toolToScopeMap = new ConcurrentHashMap<>();

    @Autowired
    public IntentToolRegistry(IntentTaxonomyProperties properties) {
        if (properties != null && properties.getIntents() != null) {
            for (IntentDefinition def : properties.getIntents()) {
                registerIntent(def);
            }
        }
    }

    public IntentToolRegistry() {
        this(new IntentTaxonomyProperties());
    }

    public void registerIntent(IntentDefinition def) {
        if (def != null && def.getId() != null) {
            intentMap.put(def.getId(), def);
            if (def.getAllowedTools() != null) {
                for (String tool : def.getAllowedTools()) {
                    if (def.getRequiredScope() != null) {
                        toolToScopeMap.put(tool, def.getRequiredScope());
                    }
                }
            }
        }
    }

    public Optional<IntentDefinition> getIntent(String intentId) {
        if (intentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(intentMap.get(intentId));
    }

    public List<Tool> allowedTools(String intentId, List<Tool> availableTools) {
        if (availableTools == null || availableTools.isEmpty()) {
            return List.of();
        }
        IntentDefinition def = intentMap.get(intentId);
        if (def == null) {
            return availableTools;
        }
        List<String> permitted = def.getAllowedTools();
        if (permitted == null || permitted.isEmpty()) {
            return List.of();
        }
        return availableTools.stream()
                .filter(t -> permitted.contains(t.name()))
                .collect(Collectors.toList());
    }

    public boolean isValid(String intentId, String toolName) {
        if (toolName == null) {
            return false;
        }
        IntentDefinition def = intentMap.get(intentId);
        if (def == null) {
            return false;
        }
        return def.getAllowedTools() != null && def.getAllowedTools().contains(toolName);
    }

    public String getRequiredScope(String toolName) {
        if (toolName == null) {
            return null;
        }
        return toolToScopeMap.get(toolName);
    }

    public boolean isMutating(String intentId) {
        IntentDefinition def = intentMap.get(intentId);
        return def != null && def.isMutating();
    }

    public double getConfidenceThreshold(String intentId) {
        IntentDefinition def = intentMap.get(intentId);
        return def != null ? def.getConfidenceThreshold() : 0.80;
    }

    public Map<String, IntentDefinition> getAllIntents() {
        return Collections.unmodifiableMap(intentMap);
    }
}
