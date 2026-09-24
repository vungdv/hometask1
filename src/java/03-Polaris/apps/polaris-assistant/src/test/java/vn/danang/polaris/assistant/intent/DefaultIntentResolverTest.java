package vn.danang.polaris.assistant.intent;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Verifies DefaultIntentResolver's orchestration: blank-input short-circuiting, delegation
 * to {@link IntentClassifier}, threshold-based tool gating, and JSON taxonomy loading.
 * The semantic quality of classification itself belongs to {@link IntentClassifier}
 * implementations (see {@link TypeSafeIntentClassifierTest}), not this class.
 */
class DefaultIntentResolverTest {

    private StubIntentClassifier classifier;
    private DefaultIntentResolver resolver;

    @BeforeEach
    void setUp() {
        classifier = new StubIntentClassifier();
        resolver = new DefaultIntentResolver(classifier);
    }

    // =========================================================================
    // 1. Happy path — main successful flows
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a non-blank query, when resolved, then delegates to the IntentClassifier and returns its classification")
        void delegates_classification_to_intent_classifier() {
            classifier.nextResult = new IntentClassification("catalog.product.search", 0.93);

            IntentClassification result = resolver.resolve("find chargers in stock", List.of());

            assertThat(result.intentId()).isEqualTo("catalog.product.search");
            assertThat(result.confidence()).isEqualTo(0.93);
            assertThat(classifier.lastQuery).isEqualTo("find chargers in stock");
            assertThat(classifier.invocationCount).isEqualTo(1);
        }

        @Test
        @DisplayName("Given classification result and available tools, when resolve called, then returns ResolvedIntent with filtered accepted tools")
        void resolves_intent_and_filters_accepted_tools() {
            classifier.nextResult = new IntentClassification("catalog.product.search", 0.93);
            Tool searchTool = Tool.builder("search_available_products", Map.of()).build();
            Tool orderTool = Tool.builder("place_order", Map.of()).build();
            List<Tool> allTools = List.of(searchTool, orderTool);

            ResolvedIntent resolved = resolver.resolve("search for wireless headphones", List.of(), allTools);

            assertThat(resolved.intentId()).isEqualTo("catalog.product.search");
            assertThat(resolved.confidence()).isEqualTo(0.93);
            assertThat(resolved.meetsThreshold()).isTrue();
            assertThat(resolved.acceptedTools()).extracting(Tool::name).containsExactly("search_available_products");
        }

        @Test
        @DisplayName("Given non-blank query, when resolved, then classifier receives the full loaded intent taxonomy")
        void passes_full_taxonomy_to_classifier() {
            classifier.nextResult = new IntentClassification("general.conversation", 0.9);

            resolver.resolve("hello", List.of());

            assertThat(classifier.lastIntents)
                    .extracting(IntentDefinition::id)
                    .contains("general.conversation", "catalog.product.search", "commerce.order.place");
        }
    }

    // =========================================================================
    // 3. Edge cases — classifier fallback behavior, history extraction
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {
        @Test
        @DisplayName("Given default constructor with no TypeSafe API key configured, when resolved, then fails closed instead of calling the network")
        void operates_safely_with_default_constructor_and_no_api_key_configured() {
            DefaultIntentResolver defaultResolver = new DefaultIntentResolver();

            IntentClassification result = defaultResolver.resolve("hello", List.of());

            assertThat(result.intentId()).isEqualTo("general.conversation");
        }

        @Test
        @DisplayName("Given a low-confidence classification below threshold, when resolve called with tools, then falls back to all available tools")
        void resolves_with_all_tools_when_confidence_below_threshold() {
            classifier.nextResult = new IntentClassification("general.conversation", 0.1);
            Tool tool1 = Tool.builder("tool_one", Map.of()).build();
            Tool tool2 = Tool.builder("tool_two", Map.of()).build();
            List<Tool> allTools = List.of(tool1, tool2);

            ResolvedIntent resolved = resolver.resolve("xyzzy completely unknown query 12345", List.of(), allTools);

            assertThat(resolved.intentId()).isEqualTo("general.conversation");
            assertThat(resolved.meetsThreshold()).isFalse();
            assertThat(resolved.acceptedTools()).containsExactlyElementsOf(allTools);
        }

        @Test
        @DisplayName("Given a custom taxonomy and classifier choice, when resolved, then returns exactly what the classifier chose")
        void returns_exactly_what_classifier_chose_for_custom_taxonomy() {
            List<IntentDefinition> customIntents = List.of(
                    new IntentDefinition("first.intent", "First", List.of("same utterance"), List.of(), null, 0.80, false),
                    new IntentDefinition("second.intent", "Second", List.of("same utterance"), List.of(), null, 0.80, false)
            );
            classifier.nextResult = new IntentClassification("first.intent", 1.0);
            DefaultIntentResolver customResolver = new DefaultIntentResolver(classifier, customIntents);

            IntentClassification result = customResolver.resolve("same utterance", List.of());

            assertThat(result.intentId()).isEqualTo("first.intent");
            assertThat(result.confidence()).isEqualTo(1.0);
        }
    }

    // =========================================================================
    // 4. JSON loading & deserialization tests
    // =========================================================================
    @Nested
    @DisplayName("4. JSON loading & deserialization")
    class JsonLoading {

        @Test
        @DisplayName("Given valid JSON array string, when loaded, then parses into IntentDefinition list")
        void loads_intents_from_valid_json_string() {
            String json = """
                    [
                      {
                        "id": "custom.order.track",
                        "description": "Track a custom order",
                        "examples": ["track custom order"],
                        "allowedTools": ["get_order_status"],
                        "requiredScope": "order.read",
                        "confidenceThreshold": 0.85,
                        "mutating": false
                      }
                    ]
                    """;

            List<IntentDefinition> intents = DefaultIntentResolver.loadIntentsFromJson(json);

            assertThat(intents).hasSize(1);
            IntentDefinition def = intents.get(0);
            assertThat(def.id()).isEqualTo("custom.order.track");
            assertThat(def.description()).isEqualTo("Track a custom order");
            assertThat(def.examples()).containsExactly("track custom order");
            assertThat(def.allowedTools()).containsExactly("get_order_status");
            assertThat(def.requiredScope()).isEqualTo("order.read");
            assertThat(def.confidenceThreshold()).isEqualTo(0.85);
            assertThat(def.mutating()).isFalse();
        }

        @Test
        @DisplayName("Given valid single IntentDefinition JSON object, when loaded, then parses correctly")
        void loads_single_intent_from_json_object() {
            String json = """
                    {
                      "id": "single.intent",
                      "description": "Single test intent",
                      "examples": ["example one"]
                    }
                    """;

            IntentDefinition def = DefaultIntentResolver.loadIntentFromJson(json);

            assertThat(def).isNotNull();
            assertThat(def.id()).isEqualTo("single.intent");
            assertThat(def.description()).isEqualTo("Single test intent");
            assertThat(def.examples()).containsExactly("example one");

            IntentDefinition fromMethod = IntentDefinition.fromJson(json);
            assertThat(fromMethod).isEqualTo(def);
        }

        @Test
        @DisplayName("Given default classpath location, when loadDefaultIntents called, then loads standard intents")
        void loads_standard_intents_from_classpath() {
            List<IntentDefinition> intents = DefaultIntentResolver.loadDefaultIntents();

            assertThat(intents).isNotEmpty();
            assertThat(intents)
                    .extracting(IntentDefinition::id)
                    .contains("general.conversation", "catalog.product.search", "commerce.order.place");
        }

        @Test
        @DisplayName("Given null or blank JSON string, when loaded, then returns empty list")
        void returns_empty_list_for_null_or_blank_json() {
            assertThat(DefaultIntentResolver.loadIntentsFromJson(null)).isEmpty();
            assertThat(DefaultIntentResolver.loadIntentsFromJson("")).isEmpty();
            assertThat(DefaultIntentResolver.loadIntentsFromJson("   ")).isEmpty();
        }

        @Test
        @DisplayName("Given malformed JSON string, when loaded, then recovers gracefully with empty list")
        void returns_empty_list_for_malformed_json() {
            String malformed = "{ this is not valid json }";

            List<IntentDefinition> result = DefaultIntentResolver.loadIntentsFromJson(malformed);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Given null input stream, when loadIntents called, then returns empty list")
        void returns_empty_list_for_null_input_stream() {
            assertThat(DefaultIntentResolver.loadIntents(null)).isEmpty();
        }

        @Test
        @DisplayName("Given reloadFromJson with new JSON string, then registry updates to new intents")
        void reloads_from_json_string() {
            DefaultIntentResolver customResolver = new DefaultIntentResolver(classifier, List.of());
            String customJson = """
                    [
                      {
                        "id": "brand.new.intent",
                        "description": "Brand new intent",
                        "examples": ["brand new query"]
                      }
                    ]
                    """;
            customResolver.reloadFromJson(customJson);

            assertThat(customResolver.getAllIntents()).containsKey("brand.new.intent");
            assertThat(customResolver.getAllIntents()).hasSize(1);
        }
    }

    private static class StubIntentClassifier implements IntentClassifier {
        IntentClassification nextResult = new IntentClassification(DefaultIntentResolver.DEFAULT_INTENT, 1.0);
        boolean shouldThrow = false;
        String lastQuery;
        Collection<IntentDefinition> lastIntents;
        int invocationCount = 0;

        @Override
        public IntentClassification classify(String query, List<AssistantMessage> history, Collection<IntentDefinition> intents) {
            invocationCount++;
            lastQuery = query;
            lastIntents = intents;
            if (shouldThrow) {
                throw new RuntimeException("stub classifier failure");
            }
            return nextResult;
        }
    }
}
