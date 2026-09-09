package vn.danang.polaris.assistant.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.entity.OrderItem;
import vn.danang.polaris.service.OrderService;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Component
public class CancelOrderReviewTool implements AssistantTool {

    private final OrderService orderService;

    public CancelOrderReviewTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
            "cancel_order_review",
            "Review an order for cancellation and inspect restock notice without executing cancellation",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "orderNumber", Map.of("type", "string", "description", "Order number to review for cancellation")
                ),
                "required", List.of("orderNumber")
            )
        );
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> arguments, SessionContext context) {
        String orderNumber = arguments.get("orderNumber") != null ? arguments.get("orderNumber").toString().trim() : null;
        if (orderNumber == null || orderNumber.isBlank()) {
            return ToolExecutionResult.failure("cancel_order_review", "Order number parameter is required.");
        }

        Order order;
        try {
            order = orderService.getOrderStatus(orderNumber);
        } catch (ResourceNotFoundException e) {
            return ToolExecutionResult.failure("cancel_order_review", e.getMessage());
        }

        if (!order.getStatus().isCancellable()) {
            Map<String, Object> problemCard = Map.of(
                "title", "Order State Conflict",
                "status", 409,
                "orderNumber", orderNumber,
                "currentStatus", order.getStatus().name(),
                "detail", "Order " + orderNumber + " cannot be cancelled — current status is " + order.getStatus() + ".",
                "allowed_states_for_action", List.of("PLACED", "CONFIRMED"),
                "remedy", "Only orders in PLACED or CONFIRMED state can be cancelled. Shipped orders must go through the return/refund workflow."
            );
            return ToolExecutionResult.failureWithWidget(
                    "cancel_order_review",
                    "Order cannot be cancelled",
                    "PROBLEM_CARD",
                    problemCard
            );
        }

        List<Map<String, Object>> restockItems = new ArrayList<>();
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() != null) {
                    restockItems.add(Map.of(
                        "sku", item.getProduct().getSku(),
                        "productName", item.getProduct().getName(),
                        "quantity", item.getQuantity()
                    ));
                }
            }
        }

        String itemSummary = restockItems.stream()
                .map(i -> i.get("quantity") + "x " + i.get("sku"))
                .collect(Collectors.joining(", "));
        String restockNotice = "Cancelling " + orderNumber + " will return " + itemSummary + " to inventory.";

        Map<String, Object> reviewData = new LinkedHashMap<>();
        reviewData.put("orderNumber", order.getOrderNumber());
        reviewData.put("currentStatus", order.getStatus().name());
        reviewData.put("cancellable", true);
        reviewData.put("restockNotice", restockNotice);
        reviewData.put("totalAmount", order.getTotalAmount());
        reviewData.put("items", restockItems);

        return ToolExecutionResult.successWithWidget(
                "cancel_order_review",
                reviewData,
                "CANCELLATION_REVIEW_CARD",
                reviewData
        );
    }
}
