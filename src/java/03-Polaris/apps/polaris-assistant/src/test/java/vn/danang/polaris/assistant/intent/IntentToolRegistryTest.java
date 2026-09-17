package vn.danang.polaris.assistant.intent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema.Tool;

class IntentToolRegistryTest {

    private IntentToolRegistry registry;

    @BeforeEach
    void setUp() {
        IntentTaxonomyProperties properties = new IntentTaxonomyProperties();
        registry = new IntentToolRegistry(properties);
    }

    @Test
    @DisplayName("Should load default intents and retrieve intent definition by ID")
    void getIntent_withKnownId_returnsDefinition() {
        assertThat(registry.getIntent(IntentClassification.CATALOG_SEARCH)).isPresent();
        assertThat(registry.getIntent(IntentClassification.ORDER_PLACE)).isPresent();
        assertThat(registry.getIntent("unknown.intent")).isEmpty();
    }

    @Test
    @DisplayName("Should filter available tools based on allowedTools for the intent")
    void allowedTools_filtersCorrectlyForIntent() {
        List<Tool> allTools = List.of(
                Tool.builder("search_available_products").build(),
                Tool.builder("get_product_by_sku").build(),
                Tool.builder("place_order").build(),
                Tool.builder("cancel_order").build()
        );

        List<Tool> searchTools = registry.allowedTools(IntentClassification.CATALOG_SEARCH, allTools);
        assertThat(searchTools).hasSize(1);
        assertThat(searchTools.get(0).name()).isEqualTo("search_available_products");

        List<Tool> placeTools = registry.allowedTools(IntentClassification.ORDER_PLACE, allTools);
        assertThat(placeTools).hasSize(1);
        assertThat(placeTools.get(0).name()).isEqualTo("place_order");

        List<Tool> generalTools = registry.allowedTools(IntentClassification.GENERAL_CONVERSATION, allTools);
        assertThat(generalTools).isEmpty();
    }

    @Test
    @DisplayName("Should return true for isValid when tool is permitted for intent")
    void isValid_returnsExpectedBoolean() {
        assertThat(registry.isValid(IntentClassification.CATALOG_SEARCH, "search_available_products")).isTrue();
        assertThat(registry.isValid(IntentClassification.CATALOG_SEARCH, "place_order")).isFalse();
        assertThat(registry.isValid(IntentClassification.ORDER_CANCEL, "cancel_order")).isTrue();
        assertThat(registry.isValid(IntentClassification.ORDER_CANCEL, "search_available_products")).isFalse();
        assertThat(registry.isValid(IntentClassification.GENERAL_CONVERSATION, "search_available_products")).isFalse();
    }

    @Test
    @DisplayName("Should return required scope mapped to tool")
    void getRequiredScope_returnsExpectedScope() {
        assertThat(registry.getRequiredScope("search_available_products")).isEqualTo("catalog.read");
        assertThat(registry.getRequiredScope("get_product_by_sku")).isEqualTo("catalog.read");
        assertThat(registry.getRequiredScope("get_order_status")).isEqualTo("order.read");
        assertThat(registry.getRequiredScope("get_order_details")).isEqualTo("order.read");
        assertThat(registry.getRequiredScope("place_order")).isEqualTo("order.write");
        assertThat(registry.getRequiredScope("cancel_order")).isEqualTo("order.write");
        assertThat(registry.getRequiredScope("unknown_tool")).isNull();
    }

    @Test
    @DisplayName("Should identify mutating intents correctly")
    void isMutating_identifiesMutatingIntents() {
        assertThat(registry.isMutating(IntentClassification.ORDER_PLACE)).isTrue();
        assertThat(registry.isMutating(IntentClassification.ORDER_CANCEL)).isTrue();
        assertThat(registry.isMutating(IntentClassification.CATALOG_SEARCH)).isFalse();
        assertThat(registry.isMutating(IntentClassification.ORDER_STATUS)).isFalse();
    }

    @Test
    @DisplayName("Should retrieve configured confidence thresholds")
    void getConfidenceThreshold_returnsConfiguredValue() {
        assertThat(registry.getConfidenceThreshold(IntentClassification.ORDER_PLACE)).isEqualTo(0.92);
        assertThat(registry.getConfidenceThreshold(IntentClassification.ORDER_CANCEL)).isEqualTo(0.92);
        assertThat(registry.getConfidenceThreshold(IntentClassification.CATALOG_SEARCH)).isEqualTo(0.80);
        assertThat(registry.getConfidenceThreshold(IntentClassification.CATALOG_LOOKUP)).isEqualTo(0.85);
        assertThat(registry.getConfidenceThreshold(IntentClassification.GENERAL_CONVERSATION)).isEqualTo(0.50);
    }
}
