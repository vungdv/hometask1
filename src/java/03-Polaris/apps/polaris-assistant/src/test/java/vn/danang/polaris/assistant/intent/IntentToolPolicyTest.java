package vn.danang.polaris.assistant.intent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    @DisplayName("Given shipped taxonomy, read-only tools never include a tool declared only by mutating intents")
    void read_only_tools_never_include_mutating_only_tools() {
        Set<String> declaredByReadOnly = new HashSet<>();
        shipped.stream().filter(def -> !def.mutating()).forEach(def -> declaredByReadOnly.addAll(def.allowedTools()));
        List<String> mutatingOnly = shipped.stream()
                .filter(IntentDefinition::mutating)
                .flatMap(def -> def.allowedTools().stream())
                .filter(tool -> !declaredByReadOnly.contains(tool))
                .toList();

        assertThat(mutatingOnly).contains("stage_order_draft", "cancel_order");
        assertThat(IntentToolPolicy.readOnlyTools(shipped)).doesNotContainAnyElementsOf(mutatingOnly);
    }

    @Test
    @DisplayName("Given shipped taxonomy, each tool maps to the scope of its declaring intents")
    void maps_each_tool_to_its_scope() {
        assertThat(IntentToolPolicy.requiredScopes("stage_order_draft", shipped)).containsExactly("order.write");
        assertThat(IntentToolPolicy.requiredScopes("cancel_order", shipped)).containsExactly("order.write");
        assertThat(IntentToolPolicy.requiredScopes("get_product_by_sku", shipped)).containsExactly("catalog.read");
        // reused by commerce.order.place, but its own scope comes from the read-only declarer
        assertThat(IntentToolPolicy.requiredScopes("search_customers_by_name", shipped)).containsExactly("order.read");
    }

    @Test
    @DisplayName("Given shipped taxonomy, place_order is declared by no intent: only the confirm endpoint places orders (S7)")
    void place_order_is_not_in_the_taxonomy() {
        assertThat(shipped).noneMatch(def -> def.allowedTools().contains("place_order"));
        assertThat(IntentToolPolicy.requiredScopes("place_order", shipped)).isEmpty();
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
