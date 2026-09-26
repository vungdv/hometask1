package vn.danang.polaris.web.exception;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared builder for the structured remedy {@code actions[]} shown to a shopper (or the AI
 * assistant on their behalf) when an order can't be placed due to insufficient stock.
 *
 * <p>Canonical owner: WO-020. Every surface that reports an {@link InsufficientStockException} —
 * the REST {@code ProblemDetail} in {@link GlobalExceptionHandler}, the MCP
 * {@code CallToolResult.structuredContent} for {@code place_order} (WO-022), and the assistant's
 * SSE {@code problem} event for {@code stage_order_draft} (WO-020) — must call this one
 * implementation so the three-action shape never drifts between surfaces.
 */
public final class InsufficientStockActions {

    private InsufficientStockActions() {
    }

    /**
     * Builds the remedy actions for an insufficient-stock failure: {@code adjust_quantity} (only
     * when some stock remains), {@code search_alternatives}, and {@code remove_item}, in that order.
     */
    public static List<Map<String, Object>> build(InsufficientStockException ex) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (ex.getAvailableQuantity() > 0) {
            actions.add(action("Adjust Quantity to " + ex.getAvailableQuantity(), "adjust_quantity",
                    Map.of("sku", ex.getSku(), "quantity", ex.getAvailableQuantity())));
        }
        actions.add(action("Search Alternatives", "search_alternatives", Map.of("query", ex.getSku())));
        actions.add(action("Remove Item", "remove_item", Map.of("sku", ex.getSku())));
        return actions;
    }

    private static Map<String, Object> action(String label, String actionId, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("action", actionId);
        m.putAll(extra);
        return m;
    }
}
