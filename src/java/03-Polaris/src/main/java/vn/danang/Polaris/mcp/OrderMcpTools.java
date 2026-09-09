package vn.danang.polaris.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.entity.OrderItem;
import vn.danang.polaris.service.OrderService;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

/**
 * Dedicated Presentation Facade exposing order management tools for MCP clients.
 */
@Component
public class OrderMcpTools {

    public static final String TOOL_GET_ORDER_STATUS = "get_order_status";
    public static final String TOOL_CANCEL_ORDER = "cancel_order";

    private static final String GET_ORDER_STATUS_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "order_number": {
              "type": "string",
              "description": "Unique business order number (e.g. 'ORD-1001')"
            }
          },
          "required": ["order_number"]
        }
        """;

    private static final String CANCEL_ORDER_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "order_number": {
              "type": "string",
              "description": "Unique business order number to cancel (e.g. 'ORD-1001')"
            }
          },
          "required": ["order_number"]
        }
        """;

    private final OrderService orderService;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public OrderMcpTools(
            OrderService orderService,
            ObjectProvider<Tracer> tracerProvider) {
        this.orderService = orderService;
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
    }

    public OrderMcpTools(OrderService orderService) {
        this(orderService, null);
    }

    public McpSchema.Tool getOrderStatusTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder()
                .name(TOOL_GET_ORDER_STATUS)
                .description("Retrieve live order fulfillment status, customer details, line items, and total amount by order number")
                .inputSchema(jsonMapper, GET_ORDER_STATUS_SCHEMA)
                .build();
    }

    public McpSchema.Tool getOrderStatusTool() {
        return getOrderStatusTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getCancelOrderTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder()
                .name(TOOL_CANCEL_ORDER)
                .description("Cancel an order in PLACED or CONFIRMED status by order number")
                .inputSchema(jsonMapper, CANCEL_ORDER_SCHEMA)
                .build();
    }

    public McpSchema.Tool getCancelOrderTool() {
        return getCancelOrderTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.CallToolResult getOrderStatus(Map<String, Object> arguments) {
        String orderNumber = getOrderNumber(arguments);
        if (orderNumber == null || orderNumber.isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Parameter 'order_number' is required.")
                    .isError(true)
                    .build();
        }
        return getOrderStatus(orderNumber);
    }

    public McpSchema.CallToolResult getOrderStatus(String orderNumber) {
        try {
            Order order = orderService.getOrderStatus(orderNumber.trim());
            String formatted = formatOrderStatus(order);
            return McpSchema.CallToolResult.builder().addTextContent(formatted).isError(false).build();
        } catch (ResourceNotFoundException ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Order not found with order number: " + orderNumber.trim())
                    .isError(true)
                    .build();
        } catch (Exception ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error retrieving order '" + orderNumber + "': " + ex.getMessage())
                    .isError(true)
                    .build();
        }
    }

    public McpSchema.CallToolResult cancelOrder(Map<String, Object> arguments) {
        String orderNumber = getOrderNumber(arguments);
        if (orderNumber == null || orderNumber.isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Parameter 'order_number' is required.")
                    .isError(true)
                    .build();
        }
        return cancelOrder(orderNumber);
    }

    public McpSchema.CallToolResult cancelOrder(String orderNumber) {
        try {
            Order order = orderService.cancelOrder(orderNumber.trim());
            String formatted = formatOrderCancelled(order);
            return McpSchema.CallToolResult.builder().addTextContent(formatted).isError(false).build();
        } catch (ResourceNotFoundException ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Order not found with order number: " + orderNumber.trim())
                    .isError(true)
                    .build();
        } catch (IllegalStateException ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Cannot cancel order: " + ex.getMessage())
                    .isError(true)
                    .build();
        } catch (Exception ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error cancelling order '" + orderNumber + "': " + ex.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private String formatOrderStatus(Order order) {
        String customerName = order.getCustomer() != null ? order.getCustomer().getFullName() : "N/A";
        List<String> lines = new ArrayList<>();
        lines.add("Order Status for " + order.getOrderNumber() + ":");
        lines.add("- Status: " + order.getStatus());
        lines.add("- Customer: " + customerName);
        lines.add("- Placed At: " + order.getPlacedAt());
        lines.add("- Total Amount: $" + order.getTotalAmount());

        List<OrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            lines.add("- Items: None");
        } else {
            lines.add(String.format("- Items (%d):", items.size()));
            for (OrderItem item : items) {
                String sku = item.getProduct() != null ? item.getProduct().getSku() : "N/A";
                String name = item.getProduct() != null ? item.getProduct().getName() : "Item";
                lines.add(String.format("  * [%s] %s x %d @ $%s", sku, name, item.getQuantity(), item.getUnitPrice()));
            }
        }

        return String.join("\n", lines);
    }

    private String formatOrderCancelled(Order order) {
        return String.format(
                "Order %s has been successfully cancelled.\n- Status: %s\n- Updated At: %s",
                order.getOrderNumber(),
                order.getStatus(),
                order.getUpdatedAt()
        );
    }

    private String getOrderNumber(Map<String, Object> arguments) {
        if (arguments == null) return null;
        Object val = arguments.get("order_number");
        if (val == null) {
            val = arguments.get("orderNumber");
        }
        return val != null ? val.toString().trim() : null;
    }
}
