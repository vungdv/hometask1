package vn.danang.polaris.assistant.intent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the tool-level policy derived from the intent taxonomy: the read-only fallback set and
 * the per-tool scope map, both against the shipped {@code intents.json} and edge-case taxonomies.
 */
class IntentToolPolicyTest {

    private final List<IntentDefinition> shipped = new DefaultIntentManager().listIntents();

    @Test
    @DisplayName("Given shipped taxonomy, read-only tools exclude place_order and cancel_order")
    void read_only_tools_exclude_mutating_tools() {
        assertThat(IntentToolPolicy.readOnlyTools(shipped))
                .contains("search_available_products", "get_product_by_sku", "get_order_status",
                        "search_customers_by_name")
                .doesNotContain("place_order", "cancel_order");
    }

    @Test
    @DisplayName("Given shipped taxonomy, each tool maps to the scope of its declaring intents")
    void maps_each_tool_to_its_scope() {
        assertThat(IntentToolPolicy.requiredScopes("place_order", shipped)).containsExactly("order.write");
        assertThat(IntentToolPolicy.requiredScopes("cancel_order", shipped)).containsExactly("order.write");
        assertThat(IntentToolPolicy.requiredScopes("get_product_by_sku", shipped)).containsExactly("catalog.read");
        // reused by commerce.order.place, but its own scope comes from the read-only declarer
        assertThat(IntentToolPolicy.requiredScopes("search_customers_by_name", shipped)).containsExactly("order.read");
    }

    @Test
    @DisplayName("Given an undeclared tool or null input, no scope is returned")
    void returns_no_scope_for_undeclared_tool() {
        assertThat(IntentToolPolicy.requiredScopes("unknown_tool", shipped)).isEmpty();
        assertThat(IntentToolPolicy.requiredScopes(null, shipped)).isEmpty();
        assertThat(IntentToolPolicy.requiredScopes("place_order", null)).isEmpty();
        assertThat(IntentToolPolicy.readOnlyTools(null)).isEmpty();
    }

    @Test
    @DisplayName("Given a tool declared by read-only intents with different scopes, all scopes are required")
    void requires_all_distinct_scopes() {
        List<IntentDefinition> intents = List.of(
                new IntentDefinition("a", "", List.of(), List.of("tool"), "scope.a", 0.8, false),
                new IntentDefinition("b", "", List.of(), List.of("tool"), "scope.b", 0.8, false),
                new IntentDefinition("c", "", List.of(), List.of("tool"), "scope.c", 0.8, true));

        assertThat(IntentToolPolicy.requiredScopes("tool", intents)).containsExactly("scope.a", "scope.b");
    }
}
