package vn.danang.polaris.assistant.widget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProblemWidgetFactory {

    private ProblemWidgetFactory() {}

    public static Map<String, Object> forInsufficientStock(
            String sku,
            String productName,
            int requestedQuantity,
            int availableQuantity) {

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "https://polaris.local/errors/out-of-stock");
        card.put("title", "Insufficient Stock");
        card.put("status", 400);
        card.put("invalid_param", "quantity");
        card.put("sku", sku);
        card.put("requested_quantity", requestedQuantity);
        card.put("available_quantity", availableQuantity);
        card.put("received", requestedQuantity);
        card.put("expected", Math.max(0, availableQuantity));
        card.put("detail", String.format("Insufficient stock for product '%s' (%s). Requested: %d, available: %d.",
                sku, productName != null ? productName : sku, requestedQuantity, availableQuantity));

        String remedy = (availableQuantity > 0)
                ? String.format("Reduce order quantity for '%s' to %d or fewer units.", sku, availableQuantity)
                : String.format("Product '%s' is currently out of stock. Search for alternative products.", sku);
        card.put("remedy", remedy);

        List<Map<String, Object>> actions = new ArrayList<>();
        if (availableQuantity > 0) {
            Map<String, Object> adjustAction = new LinkedHashMap<>();
            adjustAction.put("label", "Adjust Quantity to " + availableQuantity);
            adjustAction.put("action", "adjust_quantity");
            adjustAction.put("sku", sku);
            adjustAction.put("quantity", availableQuantity);
            actions.add(adjustAction);
        }

        Map<String, Object> altAction = new LinkedHashMap<>();
        altAction.put("label", "Search Alternatives");
        altAction.put("action", "search_alternatives");
        altAction.put("query", productName != null ? productName : sku);
        actions.add(altAction);

        card.put("actions", actions);
        return card;
    }

    public static Map<String, Object> forOrderStateConflict(String orderNumber, String status) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "https://polaris.local/errors/conflict");
        card.put("title", "Order State Conflict");
        card.put("status", 409);
        card.put("invalid_param", "status");
        card.put("orderNumber", orderNumber);
        card.put("currentStatus", status);
        card.put("received", status);
        card.put("allowed_values", List.of("PLACED", "CONFIRMED"));
        card.put("allowed_states_for_action", List.of("PLACED", "CONFIRMED"));
        card.put("detail", String.format("Order %s cannot be cancelled — current status is %s.", orderNumber, status));
        card.put("remedy", "Only orders in PLACED or CONFIRMED state can be cancelled. Shipped orders must go through the return/refund workflow.");

        List<Map<String, Object>> actions = List.of(
                Map.of(
                        "label", "Return Guidelines",
                        "action", "view_returns"
                )
        );
        card.put("actions", actions);
        return card;
    }

    public static Map<String, Object> forDraftExpired(String draftId) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "https://polaris.local/errors/draft-expired");
        card.put("title", "Draft Expired");
        card.put("status", 409);
        card.put("invalid_param", "draftId");
        card.put("received", draftId);
        card.put("detail", String.format("Order draft %s has expired (15-minute TTL elapsed).", draftId));
        card.put("remedy", "The order draft has expired (15-minute TTL elapsed). Please stage a new order draft.");

        List<Map<String, Object>> actions = List.of(
                Map.of(
                        "label", "Refresh Draft",
                        "action", "refresh_draft"
                )
        );
        card.put("actions", actions);
        return card;
    }

    public static Map<String, Object> forForbidden(String invalidParam, Object received, String detail) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "https://polaris.local/errors/forbidden");
        card.put("title", "Forbidden");
        card.put("status", 403);
        card.put("invalid_param", invalidParam);
        card.put("received", received);
        card.put("detail", detail != null ? detail : "Access denied: You do not have permission to view or manage resources for another customer.");
        card.put("remedy", "You are only permitted to access orders and drafts belonging to your account.");

        List<Map<String, Object>> actions = List.of(
                Map.of(
                        "label", "My Orders",
                        "action", "view_my_orders"
                )
        );
        card.put("actions", actions);
        return card;
    }

    public static Map<String, Object> forCustomerNotFound(Long customerId) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "https://polaris.local/errors/not-found");
        card.put("title", "Customer Not Found");
        card.put("status", 404);
        card.put("invalid_param", "customerId");
        card.put("received", customerId);
        card.put("detail", "Customer not found with ID: " + customerId);
        card.put("remedy", "Verify the customer ID and try again.");
        card.put("actions", List.of());
        return card;
    }
}
