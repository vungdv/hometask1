package vn.danang.polaris.assistant.tool;

import java.util.Map;

import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.dto.OrderResponse;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.service.OrderService;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Component
public class GetOrderStatusTool implements AssistantTool {

    private final OrderService orderService;

    public GetOrderStatusTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
            "get_order_status",
            "Retrieve status, items, and tracking details for a specific order number",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "orderNumber", Map.of("type", "string", "description", "Order number (e.g. ORD-1001)")
                ),
                "required", java.util.List.of("orderNumber")
            )
        );
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> arguments, SessionContext context) {
        String orderNumber = arguments.get("orderNumber") != null ? arguments.get("orderNumber").toString().trim() : null;
        if (orderNumber == null || orderNumber.isBlank()) {
            return ToolExecutionResult.failure("get_order_status", "Order number parameter is required.");
        }

        try {
            Order order = orderService.getOrderStatus(orderNumber);
            OrderResponse response = OrderResponse.from(order);
            return ToolExecutionResult.successWithWidget(
                    "get_order_status",
                    response,
                    "ORDER_STATUS_CARD",
                    response
            );
        } catch (ResourceNotFoundException e) {
            return ToolExecutionResult.failure("get_order_status", e.getMessage());
        }
    }
}
