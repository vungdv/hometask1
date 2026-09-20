package vn.danang.polaris.assistant.intent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.modelcontextprotocol.spec.McpSchema.Tool;

class IntentToolRegistryTest {

    private IntentToolRegistry registry;

    @BeforeEach
    void setUp() {
        IntentTaxonomyProperties properties = new IntentTaxonomyProperties();
        registry = new IntentToolRegistry(properties);
    }

    // =========================================================================
    // 1. Happy path — standard taxonomy mappings
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @ParameterizedTest(name = "Registered intent \"{0}\" exists in taxonomy")
        @CsvSource({
                "catalog.product.search",
                "catalog.product.lookup",
                "information.lookup.order.status",
                "information.lookup.order.details",
                "information.lookup.order.history",
                "commerce.order.place",
                "commerce.order.cancel",
                "general.conversation"
        })
        @DisplayName("Given registered intent ID, when queried, then returns populated IntentDefinition")
        void retrieves_definition_for_registered_intents(String intentId) {
            assertThat(registry.getIntent(intentId)).isPresent();
        }

        @Test
        @DisplayName("Given available tools list, when filtered by intent, then returns only tools allowed for that intent")
        void filters_available_tools_for_intent() {
            List<Tool> allTools = List.of(
                    Tool.builder("search_available_products").build(),
                    Tool.builder("get_product_by_sku").build(),
                    Tool.builder("place_order").build(),
                    Tool.builder("cancel_order").build()
            );

            List<Tool> searchTools = registry.allowedTools(IntentClassification.CATALOG_SEARCH, allTools);

            assertThat(searchTools)
                    .extracting(Tool::name)
                    .containsExactly("search_available_products");
        }

        @ParameterizedTest(name = "Tool \"{1}\" is valid for intent \"{0}\"")
        @CsvSource({
                "catalog.product.search, search_available_products",
                "catalog.product.lookup, get_product_by_sku",
                "commerce.order.place, place_order",
                "commerce.order.cancel, cancel_order"
        })
        @DisplayName("Given authorized tool and intent pairing, when validated, then returns true")
        void validates_permitted_tools_for_intent(String intentId, String toolName) {
            assertThat(registry.isValid(intentId, toolName)).isTrue();
        }

        @ParameterizedTest(name = "Tool \"{0}\" maps to scope \"{1}\"")
        @CsvSource({
                "search_available_products, catalog.read",
                "get_product_by_sku, catalog.read",
                "get_order_status, order.read",
                "get_order_details, order.read",
                "place_order, order.write",
                "cancel_order, order.write"
        })
        @DisplayName("Given known tool name, when querying required scope, then returns mapped OAuth2 scope")
        void maps_required_scope_for_known_tools(String toolName, String expectedScope) {
            assertThat(registry.getRequiredScope(toolName)).isEqualTo(expectedScope);
        }
    }

    // =========================================================================
    // 2. Invalid input & unmapped queries
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & unmapped queries")
    class InvalidInput {

        @Test
        @DisplayName("Given unknown intent ID, when queried, then returns empty Optional")
        void returns_empty_for_unknown_intent() {
            assertThat(registry.getIntent("non_existent_intent")).isEmpty();
        }

        @ParameterizedTest(name = "Tool \"{1}\" is forbidden for intent \"{0}\"")
        @CsvSource({
                "catalog.product.search, place_order",
                "commerce.order.cancel, search_available_products",
                "general.conversation, search_available_products"
        })
        @DisplayName("Given mismatched tool and intent, when validated, then returns false")
        void rejects_unpermitted_tool_for_intent(String intentId, String toolName) {
            assertThat(registry.isValid(intentId, toolName)).isFalse();
        }

        @Test
        @DisplayName("Given unregistered tool name, when querying required scope, then returns null")
        void returns_null_for_unregistered_tool_scope() {
            assertThat(registry.getRequiredScope("unregistered_tool_xyz")).isNull();
        }
    }

    // =========================================================================
    // 3. Edge cases — mutating checks, thresholds, conversational tools
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @ParameterizedTest(name = "Intent \"{0}\" mutating flag is {1}")
        @CsvSource({
                "commerce.order.place, true",
                "commerce.order.cancel, true",
                "catalog.product.search, false",
                "information.lookup.order.status, false",
                "general.conversation, false"
        })
        @DisplayName("Given intent ID, when checking isMutating, then correctly distinguishes state-modifying actions")
        void identifies_mutating_intents(String intentId, boolean expectedMutating) {
            assertThat(registry.isMutating(intentId)).isEqualTo(expectedMutating);
        }

        @ParameterizedTest(name = "Intent \"{0}\" confidence threshold is {1}")
        @CsvSource({
                "commerce.order.place, 0.92",
                "commerce.order.cancel, 0.92",
                "catalog.product.search, 0.80",
                "catalog.product.lookup, 0.85",
                "general.conversation, 0.50"
        })
        @DisplayName("Given intent ID, when querying confidence threshold, then returns configured value")
        void retrieves_configured_confidence_thresholds(String intentId, double expectedThreshold) {
            assertThat(registry.getConfidenceThreshold(intentId)).isEqualTo(expectedThreshold);
        }

        @Test
        @DisplayName("Given general conversation intent, when filtering tools, then returns empty list")
        void returns_empty_tools_for_general_conversation() {
            List<Tool> allTools = List.of(
                    Tool.builder("search_available_products").build(),
                    Tool.builder("place_order").build()
            );

            List<Tool> generalTools = registry.allowedTools(IntentClassification.GENERAL_CONVERSATION, allTools);

            assertThat(generalTools).isEmpty();
        }
    }
}
