package vn.danang.polaris.assistant.intent;

import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.text.similarity.CosineSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/**
 * Merged intent resolver and tool registry.
 * Implements {@link IntentResolver} to resolve user intent via Apache Commons Text
 * {@link CosineSimilarity} matching against dynamically loaded intents, and to filter
 * available MCP tools based on intent policy and confidence thresholds.
 */
@Component
@Primary
public class DefaultIntentResolver implements IntentResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultIntentResolver.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String DEFAULT_INTENT = "general.conversation";
    private static final String CATALOG_LOOKUP_INTENT = "catalog.product.lookup";

    private final CosineSimilarity cosineSimilarity = new CosineSimilarity();
    private final Map<String, IntentDefinition> intentMap = new ConcurrentHashMap<>();
    private final Map<String, String> toolToScopeMap = new ConcurrentHashMap<>();

    @Autowired
    public DefaultIntentResolver() {
        this(loadDefaultIntents());
    }

    public DefaultIntentResolver(Collection<IntentDefinition> intents) {
        initRegistry(intents);
    }

    private void initRegistry(Collection<IntentDefinition> definitions) {
        if (definitions != null) {
            for (IntentDefinition def : definitions) {
                registerIntent(def);
            }
        }
    }

    // =========================================================================
    // Core Resolution: IntentResolver Implementation
    // =========================================================================

    @Override
    public ResolvedIntent resolve(String messageText, List<AssistantMessage> history, List<Tool> tools) {
        IntentClassification classification = resolveClassification(messageText, history);
        return resolveIntent(classification, tools);
    }

    /**
     * Resolves user intent classification without tool filtering.
     *
     * @param userMessage the raw user utterance
     * @param context conversation history in the current session
     * @return IntentClassification containing the resolved intent ID and confidence score
     */
    public IntentClassification resolve(String userMessage, List<AssistantMessage> context) {
        return resolveClassification(userMessage, context);
    }

    /**
     * Matches user message and history against intent examples using Apache Commons Text CosineSimilarity.
     *
     * @param userMessage raw user prompt
     * @param context conversation history
     * @return IntentClassification with intentId and confidence score
     */
    public IntentClassification resolveClassification(String userMessage, List<AssistantMessage> context) {
        String query = userMessage != null ? userMessage.trim() : "";
        if (query.isBlank() && context != null && !context.isEmpty()) {
            for (int i = context.size() - 1; i >= 0; i--) {
                AssistantMessage msg = context.get(i);
                if (msg != null && msg.getRole() == MessageRole.USER && msg.getContent() != null && !msg.getContent().isBlank()) {
                    query = msg.getContent().trim();
                    break;
                }
            }
        }

        if (query.isBlank()) {
            return new IntentClassification(DEFAULT_INTENT, 1.0);
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        Map<CharSequence, Integer> qTokens = toTokens(lowerQuery);

        IntentDefinition bestIntent = null;
        double highestScore = -1.0;

        List<IntentDefinition> intents = !intentMap.isEmpty()
                ? List.copyOf(intentMap.values())
                : loadDefaultIntents();

        for (IntentDefinition def : intents) {
            // Guard: order numbers (e.g. ORD-1001) are not product catalog SKUs
            if (lowerQuery.contains("ord-") && CATALOG_LOOKUP_INTENT.equals(def.id())) {
                continue;
            }

            double intentScore = 0.0;
            for (String example : def.examples()) {
                if (example == null || example.isBlank()) {
                    continue;
                }
                String lowerEx = example.trim().toLowerCase(Locale.ROOT);
                Map<CharSequence, Integer> exTokens = toTokens(lowerEx);

                double score;
                if (lowerQuery.equals(lowerEx)) {
                    score = 1.0;
                } else if (lowerQuery.contains(lowerEx)) {
                    double cos = 0.0;
                    try {
                        cos = cosineSimilarity.cosineSimilarity(qTokens, exTokens);
                    } catch (Exception ignored) {
                    }
                    score = Math.max(0.95, cos);
                } else {
                    try {
                        score = cosineSimilarity.cosineSimilarity(qTokens, exTokens);
                    } catch (Exception ignored) {
                        score = 0.0;
                    }
                }

                if (score > intentScore) {
                    intentScore = score;
                }
            }

            // Return first highest score matching
            if (intentScore > highestScore) {
                highestScore = intentScore;
                bestIntent = def;
            }
        }

        if (bestIntent != null && highestScore >= 0.50) {
            log.debug("Resolved intent [{}] with confidence [{}] for query [{}]", bestIntent.id(), highestScore, query);
            return new IntentClassification(bestIntent.id(), highestScore);
        }

        log.debug("No confident intent found (score: [{}]). Falling back to general.conversation", highestScore);
        return new IntentClassification(DEFAULT_INTENT, Math.max(0.0, highestScore));
    }

    // =========================================================================
    // Tool Registry & Policy Mapping Methods
    // =========================================================================

    public void registerIntent(IntentDefinition def) {
        if (def != null && def.id() != null) {
            intentMap.put(def.id(), def);
            if (def.allowedTools() != null) {
                for (String tool : def.allowedTools()) {
                    if (def.requiredScope() != null) {
                        toolToScopeMap.putIfAbsent(tool, def.requiredScope());
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
        List<String> permitted = def.allowedTools();
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
        return def.allowedTools() != null && def.allowedTools().contains(toolName);
    }

    public String getRequiredScope(String toolName) {
        if (toolName == null) {
            return null;
        }
        return toolToScopeMap.get(toolName);
    }

    public boolean isMutating(String intentId) {
        IntentDefinition def = intentMap.get(intentId);
        return def != null && def.mutating();
    }

    public double getConfidenceThreshold(String intentId) {
        IntentDefinition def = intentMap.get(intentId);
        return def != null ? def.confidenceThreshold() : 0.80;
    }

    public ResolvedIntent resolveIntent(IntentClassification classification, List<Tool> availableTools) {
        String intentId = classification != null && classification.intentId() != null && !classification.intentId().isBlank()
                ? classification.intentId()
                : DEFAULT_INTENT;
        double confidence = classification != null ? classification.confidence() : 1.0;
        return resolveIntent(intentId, confidence, availableTools);
    }

    public ResolvedIntent resolveIntent(String intentId, double confidence, List<Tool> availableTools) {
        String effectiveIntentId = (intentId != null && !intentId.isBlank())
                ? intentId
                : DEFAULT_INTENT;
        double threshold = getConfidenceThreshold(effectiveIntentId);
        boolean meetsThreshold = confidence >= threshold;

        List<Tool> tools = (availableTools != null) ? availableTools : List.of();
        List<Tool> acceptedTools = !meetsThreshold
                ? tools
                : allowedTools(effectiveIntentId, tools);

        IntentDefinition def = intentMap.get(effectiveIntentId);

        return new ResolvedIntent(effectiveIntentId, confidence, meetsThreshold, acceptedTools, def);
    }

    public ResolvedIntent resolve(IntentClassification classification, List<Tool> availableTools) {
        return resolveIntent(classification, availableTools);
    }

    public ResolvedIntent resolve(String intentId, double confidence, List<Tool> availableTools) {
        return resolveIntent(intentId, confidence, availableTools);
    }

    public Map<String, IntentDefinition> getAllIntents() {
        return Collections.unmodifiableMap(intentMap);
    }

    public void reload(Collection<IntentDefinition> newIntents) {
        intentMap.clear();
        toolToScopeMap.clear();
        if (newIntents != null) {
            for (IntentDefinition def : newIntents) {
                registerIntent(def);
            }
        }
    }

    public void reload() {
        reload(loadDefaultIntents());
    }

    public void reloadFromJson(String jsonContent) {
        reload(loadIntentsFromJson(jsonContent));
    }

    // =========================================================================
    // JSON Deserialization Functions
    // =========================================================================

    /**
     * Loads default intents from the classpath {@code intents.json} resource.
     *
     * @return unmodifiable list of default {@link IntentDefinition}s
     */
    public static List<IntentDefinition> loadDefaultIntents() {
        try (InputStream is = DefaultIntentResolver.class.getClassLoader().getResourceAsStream("intents.json")) {
            if (is != null) {
                return loadIntents(is);
            }
            log.warn("Classpath resource [intents.json] not found");
        } catch (Exception e) {
            log.error("Failed to load default intents from classpath:intents.json: {}", e.getMessage(), e);
        }
        return List.of();
    }

    /**
     * Deserializes intents from an {@link InputStream}.
     *
     * @param inputStream the stream containing JSON array
     * @return unmodifiable list of loaded {@link IntentDefinition}s
     */
    public static List<IntentDefinition> loadIntents(InputStream inputStream) {
        if (inputStream == null) {
            return List.of();
        }
        try {
            IntentDefinition[] array = OBJECT_MAPPER.readValue(inputStream, IntentDefinition[].class);
            return array != null ? List.of(array) : List.of();
        } catch (Exception e) {
            log.error("Failed to parse intents from InputStream: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Deserializes intents from a raw JSON string.
     *
     * @param jsonContent the JSON string content
     * @return unmodifiable list of loaded {@link IntentDefinition}s
     */
    public static List<IntentDefinition> loadIntentsFromJson(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return List.of();
        }
        try {
            if (jsonContent.trim().startsWith("[")) {
                IntentDefinition[] array = OBJECT_MAPPER.readValue(jsonContent, IntentDefinition[].class);
                return array != null ? List.of(array) : List.of();
            }
            IntentDefinition single = OBJECT_MAPPER.readValue(jsonContent, IntentDefinition.class);
            return single != null ? List.of(single) : List.of();
        } catch (Exception e) {
            log.error("Failed to parse intents JSON string: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Deserializes a single intent from a JSON string.
     *
     * @param jsonContent the JSON string content
     * @return loaded {@link IntentDefinition}, or null on failure
     */
    public static IntentDefinition loadIntentFromJson(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(jsonContent, IntentDefinition.class);
        } catch (Exception e) {
            log.error("Failed to parse intent JSON string: {}", e.getMessage(), e);
            return null;
        }
    }

    private Map<CharSequence, Integer> toTokens(String text) {
        Map<CharSequence, Integer> map = new HashMap<>();
        String[] words = text.replaceAll("[^a-zA-Z0-9\\s-]", " ").split("\\s+");
        for (String w : words) {
            if (!w.isBlank()) {
                map.merge(w, 1, Integer::sum);
            }
        }
        return map;
    }
}
