package vn.danang.polaris.assistant.intent;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.config.AssistantTypeSafeProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Forwards classification requests to an {@link IntentClassifier}, turning its result into a
 * {@link ResolvedIntent} scoped to the matched {@link IntentDefinition}. The intent taxonomy
 * itself is provided by an {@link IntentManager}, which abstracts over where intents actually
 * live (local classpath resource by default, a remote store in other implementations).
 */
@Component
@Primary
public class DefaultIntentResolver implements IntentResolver {
    public static final String DEFAULT_INTENT = "general.conversation";

    private final IntentClassifier intentClassifier;
    private final IntentManager intentManager;

    @Autowired
    public DefaultIntentResolver(IntentClassifier intentClassifier, IntentManager intentManager) {
        this.intentClassifier = intentClassifier != null ? intentClassifier : defaultClassifier();
        this.intentManager = intentManager != null ? intentManager : defaultIntentManager();
    }

    public DefaultIntentResolver(IntentClassifier intentClassifier) {
        this(intentClassifier, defaultIntentManager());
    }

    public DefaultIntentResolver() {
        this(defaultClassifier(), defaultIntentManager());
    }

    public DefaultIntentResolver(IntentClassifier intentClassifier, Collection<IntentDefinition> intents) {
        this(intentClassifier, new DefaultIntentManager(intents != null ? List.copyOf(intents) : List.of()));
    }

    private static IntentClassifier defaultClassifier() {
        return new TypeSafeIntentClassifier(new AssistantTypeSafeProperties());
    }

    private static IntentManager defaultIntentManager() {
        return new DefaultIntentManager();
    }

    @Override
    public ResolvedIntent resolve(String messageText, List<AssistantMessage> history, List<Tool> tools) {
        List<IntentDefinition> intents = intentManager.listIntents();
        IntentClassification classification = intentClassifier.classify(messageText, history, intents);
        String intentId = classification.intentId();

        IntentDefinition definition = intentManager.getIntent(intentId).orElse(IntentDefinition.empty());

        double confidence = classification.confidence();
        double threshold = definition.confidenceThreshold();
        boolean meetsThreshold = confidence >= threshold;

        List<Tool> acceptedTools = meetsThreshold ? filterTools(definition, tools) : tools;

        return new ResolvedIntent(intentId, confidence, meetsThreshold, acceptedTools, definition);
    }

    /**
     * Classifies user message and history against the loaded intent taxonomy, without any
     * tool filtering.
     */
    public IntentClassification resolve(String messageText, List<AssistantMessage> history) {
        return intentClassifier.classify(messageText, history, intentManager.listIntents());
    }

    private List<Tool> filterTools(IntentDefinition definition, List<Tool> availableTools) {
        if (definition == null) {
            return availableTools;
        }
        List<String> permitted = definition.allowedTools();
        if (permitted == null || permitted.isEmpty()) {
            return List.of();
        }
        return availableTools.stream()
                .filter(t -> permitted.contains(t.name()))
                .toList();
    }
}
