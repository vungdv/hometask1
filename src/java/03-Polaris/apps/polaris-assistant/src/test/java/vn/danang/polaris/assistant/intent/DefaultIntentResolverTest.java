package vn.danang.polaris.assistant.intent;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultIntentResolverTest {

    private DefaultIntentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultIntentResolver();
    }

    // =========================================================================
    // 1. Happy path — main successful flows
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @ParameterizedTest(name = "Query \"{0}\" resolves to CATALOG_SEARCH")
        @ValueSource(strings = {
                "show me running shoes under $100",
                "find chargers in stock",
                "browse electronics catalog",
                "search for wireless headphones"
        })
        @DisplayName("Given catalog search queries, when resolved, then classifies as CATALOG_SEARCH with high confidence")
        void resolves_catalog_search_for_product_search_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo("catalog.product.search");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.80);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to CATALOG_LOOKUP")
        @ValueSource(strings = {
                "tell me more about SM-PH-001",
                "is SKU NG-CHARGER-02 in stock",
                "product details for AP-MACBOOK-16"
        })
        @DisplayName("Given specific SKU mentions, when resolved, then classifies as CATALOG_LOOKUP with high confidence")
        void resolves_catalog_lookup_for_sku_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo("catalog.product.lookup");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_STATUS")
        @ValueSource(strings = {
                "where is my order ORD-1001",
                "status of order 1234",
                "track order ORD-5555"
        })
        @DisplayName("Given order tracking queries, when resolved, then classifies as ORDER_STATUS")
        void resolves_order_status_for_tracking_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo("information.lookup.order.status");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_DETAILS")
        @ValueSource(strings = {
                "what did I order in ORD-1001",
                "show me the full details of my last order",
                "order contents for ORD-1001"
        })
        @DisplayName("Given order breakdown queries, when resolved, then classifies as ORDER_DETAILS")
        void resolves_order_details_for_item_breakdown_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo("information.lookup.order.details");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_HISTORY")
        @ValueSource(strings = {
                "show my order history",
                "list my cancelled orders",
                "previous purchases"
        })
        @DisplayName("Given order history queries, when resolved, then classifies as ORDER_HISTORY")
        void resolves_order_history_for_past_purchase_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo("information.lookup.order.history");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.80);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_PLACE")
        @ValueSource(strings = {
                "order 2 of NG-EARBUD-01",
                "buy the wireless earbuds",
                "place order for Alice Tran",
                "order 2 of NG-EARBUD-01 for Alice"
        })
        @DisplayName("Given purchasing queries, when resolved, then classifies as ORDER_PLACE")
        void resolves_order_place_for_purchase_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo("commerce.order.place");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.92);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_CANCEL")
        @ValueSource(strings = {
                "cancel my order ORD-1001",
                "I want to cancel that last order",
                "abort order ORD-999"
        })
        @DisplayName("Given cancellation queries, when resolved, then classifies as ORDER_CANCEL")
        void resolves_order_cancel_for_cancellation_requests(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo("commerce.order.cancel");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.92);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to CUSTOMER_LOOKUP")
        @ValueSource(strings = {
                "find customer Alice",
                "who is customer Chi",
                "my name is Alice Tran"
        })
        @DisplayName("Given customer identification queries, when resolved, then classifies as CUSTOMER_LOOKUP")
        void resolves_customer_lookup_for_customer_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo("customer.lookup.by_name");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to GENERAL_CONVERSATION")
        @ValueSource(strings = {
                "hello",
                "hi there",
                "who are you?",
                "what can you do"
        })
        @DisplayName("Given conversational greetings and help queries, when resolved, then classifies as GENERAL_CONVERSATION")
        void resolves_general_conversation_for_greetings(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo("general.conversation");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        }

        @Test
        @DisplayName("Given catalog search query and available tools, when resolve called, then returns ResolvedIntent with filtered accepted tools")
        void resolves_intent_and_filters_accepted_tools() {
            Tool searchTool = Tool.builder("search_available_products", Map.of()).build();
            Tool orderTool = Tool.builder("place_order", Map.of()).build();
            List<Tool> allTools = List.of(searchTool, orderTool);

            ResolvedIntent resolved = resolver.resolve("search for wireless headphones", List.of(), allTools);

            assertThat(resolved.intentId()).isEqualTo("catalog.product.search");
            assertThat(resolved.confidence()).isGreaterThanOrEqualTo(0.80);
            assertThat(resolved.meetsThreshold()).isTrue();
            assertThat(resolved.acceptedTools()).extracting(Tool::name).containsExactly("search_available_products");
        }
    }

    // =========================================================================
    // 2. Invalid input — common validation and boundary cases
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @ParameterizedTest(name = "Blank query \"{0}\" resolves to GENERAL_CONVERSATION")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n"})
        @DisplayName("Given null, empty, or whitespace-only queries, when resolved, then returns GENERAL_CONVERSATION with full confidence")
        void resolves_null_empty_or_whitespace_as_general_conversation(String blankQuery) {
            IntentClassification result = resolver.resolve(blankQuery, List.of());

            assertThat(result.intentId()).isEqualTo("general.conversation");
            assertThat(result.confidence()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Given all null arguments, when resolve called, then returns GENERAL_CONVERSATION with empty accepted tools safely")
        void resolves_safely_when_all_arguments_are_null() {
            ResolvedIntent resolved = resolver.resolve(null, null, null);

            assertThat(resolved.intentId()).isEqualTo("general.conversation");
            assertThat(resolved.acceptedTools()).isEmpty();
        }
    }

    // =========================================================================
    // 3. Edge cases — boundaries, order vs SKU disambiguation, unknown queries
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given order identifier ORD-1001, when resolving inquiry, then does not classify as CATALOG_LOOKUP")
        void does_not_treat_order_number_as_catalog_lookup() {
            IntentClassification result = resolver.resolve("sku ORD-1001", List.of());

            assertThat(result.intentId()).isNotEqualTo("catalog.product.lookup");
        }

        @Test
        @DisplayName("Given completely unknown query, when resolved, then falls back to GENERAL_CONVERSATION with low confidence")
        void falls_back_to_general_conversation_with_low_confidence_for_unknown_input() {
            IntentClassification result = resolver.resolve("xyzzy qwerty foobar 98765", List.of());

            assertThat(result.intentId()).isEqualTo("general.conversation");
            assertThat(result.confidence()).isLessThan(0.50);
        }

        @Test
        @DisplayName("Given mixed case greeting query, when resolved, then correctly classifies as GENERAL_CONVERSATION")
        void handles_case_insensitive_greetings() {
            IntentClassification result = resolver.resolve("hElLo ThErE", List.of());

            assertThat(result.intentId()).isEqualTo("general.conversation");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        }

        @Test
        @DisplayName("Given default constructor without explicit properties, when instantiated, then resolves intents correctly")
        void operates_correctly_with_default_constructor() {
            DefaultIntentResolver defaultResolver = new DefaultIntentResolver();
            IntentClassification result = defaultResolver.resolve("hello", List.of());

            assertThat(result.intentId()).isEqualTo("general.conversation");
        }

        @Test
        @DisplayName("Given blank messageText but non-empty history, when resolved, then extracts and matches user message from history")
        void resolves_from_history_when_message_text_is_blank() {
            vn.danang.polaris.assistant.entity.AssistantMessage historyMsg =
                    vn.danang.polaris.assistant.entity.AssistantMessage.of("track order ORD-5555");
            IntentClassification result = resolver.resolve("", List.of(historyMsg));

            assertThat(result.intentId()).isEqualTo("information.lookup.order.status");
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        }

        @Test
        @DisplayName("Given multiple candidate intents with equal match score, when resolved, then returns the first highest match")
        void returns_first_highest_score_matching() {
            List<IntentDefinition> customIntents = List.of(
                    new IntentDefinition("first.intent", "First", List.of("same utterance"), List.of(), null, 0.80, false),
                    new IntentDefinition("second.intent", "Second", List.of("same utterance"), List.of(), null, 0.80, false)
            );
            DefaultIntentResolver customResolver = new DefaultIntentResolver(customIntents);

            IntentClassification result = customResolver.resolve("same utterance", List.of());

            assertThat(result.intentId()).isEqualTo("first.intent");
            assertThat(result.confidence()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Given low confidence query below threshold, when resolve called with tools, then falls back to all available tools")
        void resolves_with_all_tools_when_confidence_below_threshold() {
            Tool tool1 = Tool.builder("tool_one", Map.of()).build();
            Tool tool2 = Tool.builder("tool_two", Map.of()).build();
            List<Tool> allTools = List.of(tool1, tool2);

            ResolvedIntent resolved = resolver.resolve("xyzzy completely unknown query 12345", List.of(), allTools);

            assertThat(resolved.intentId()).isEqualTo("general.conversation");
            assertThat(resolved.meetsThreshold()).isFalse();
            assertThat(resolved.acceptedTools()).containsExactlyElementsOf(allTools);
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
        @DisplayName("Given valid JSON object with intents array wrapper, when loaded, then parses correctly")
        void loads_intents_from_wrapped_json_object() {
            String json = """
                    {
                      "intents": [
                        {
                          "id": "wrapped.intent",
                          "description": "Wrapped test intent",
                          "examples": ["example one"]
                        }
                      ]
                    }
                    """;

            List<IntentDefinition> intents = DefaultIntentResolver.loadIntentsFromJson(json);

            assertThat(intents).hasSize(1);
            assertThat(intents.get(0).id()).isEqualTo("wrapped.intent");
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
            DefaultIntentResolver customResolver = new DefaultIntentResolver();
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
}

