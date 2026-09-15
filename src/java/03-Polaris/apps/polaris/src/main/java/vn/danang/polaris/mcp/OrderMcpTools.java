package vn.danang.polaris.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.dto.OrderResponse;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.entity.OrderItem;
import vn.danang.polaris.order.entity.OrderStatus;
import org.springframework.transaction.annotation.Transactional;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

/**
 * Dedicated Presentation Facade exposing order management tools for MCP clients.
 */
@Component
public class OrderMcpTools {

    public static final String TOOL_GET_ORDER_STATUS = "get_order_status";
    public static final String TOOL_GET_ORDER_DETAILS = "get_order_details";
    public static final String TOOL_PLACE_ORDER = "place_order";
    public static final String TOOL_LIST_CUSTOMER_ORDERS = "list_customer_orders";
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

    private static final String GET_ORDER_DETAILS_SCHEMA = """
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

    private static final String PLACE_ORDER_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "customer_id": {
              "type": "integer",
              "description": "Unique numeric ID of the customer placing the order"
            },
            "items": {
              "type": "array",
              "description": "List of line items to order",
              "items": {
                "type": "object",
                "properties": {
                  "sku": {
                    "type": "string",
                    "description": "Product SKU code (e.g. 'NG-EARBUD-01')"
                  },
                  "quantity": {
                    "type": "integer",
                    "description": "Quantity to order (minimum 1)"
                  }
                },
                "required": ["sku", "quantity"]
              }
            },
            "idempotency_key": {
              "type": "string",
              "description": "Optional unique idempotency key to prevent duplicate orders during retries"
            }
          },
          "required": ["customer_id", "items"]
        }
        """;

    private static final String LIST_CUSTOMER_ORDERS_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "customer_id": {
              "type": "integer",
              "description": "Unique numeric customer ID"
            },
            "status": {
              "type": "string",
              "description": "Optional order lifecycle status filter (PLACED, CONFIRMED, PARCELED, DELIVERING, DELIVERED, CANCELLED)"
            },
            "page": {
              "type": "integer",
              "description": "Zero-based page index (default: 0)"
            },
            "size": {
              "type": "integer",
              "description": "Number of records per page (default: 20)"
            }
          },
          "required": ["customer_id"]
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

    public McpSchema.Tool getOrderDetailsTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder()
                .name(TOOL_GET_ORDER_DETAILS)
                .description("Retrieve full order details, line items, pricing, and fulfillment status by order number")
                .inputSchema(jsonMapper, GET_ORDER_DETAILS_SCHEMA)
                .build();
    }

    public McpSchema.Tool getOrderDetailsTool() {
        return getOrderDetailsTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getPlaceOrderTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder()
                .name(TOOL_PLACE_ORDER)
                .description("Place a new multi-item order for a customer with atomic stock verification and idempotency protection")
                .inputSchema(jsonMapper, PLACE_ORDER_SCHEMA)
                .build();
    }

    public McpSchema.Tool getPlaceOrderTool() {
        return getPlaceOrderTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getListCustomerOrdersTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder()
                .name(TOOL_LIST_CUSTOMER_ORDERS)
                .description("Search and list order history for a customer with optional status filter and pagination")
                .inputSchema(jsonMapper, LIST_CUSTOMER_ORDERS_SCHEMA)
                .build();
    }

    public McpSchema.Tool getListCustomerOrdersTool() {
        return getListCustomerOrdersTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getCancelOrderTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder()
                .name(TOOL_CANCEL_ORDER)
                .description("Cancel an order in PLACED or CONFIRMED status by order number, restoring inventory stock")
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

    @Transactional(readOnly = true)
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

    public McpSchema.CallToolResult getOrderDetails(Map<String, Object> arguments) {
        return getOrderStatus(arguments);
    }

    public McpSchema.CallToolResult placeOrder(Map<String, Object> arguments) {
        if (arguments == null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Arguments are required.")
                    .isError(true)
                    .build();
        }

        Long customerId = parseLong(arguments.get("customer_id") != null ? arguments.get("customer_id") : arguments.get("customerId"));
        if (customerId == null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Parameter 'customer_id' is required.")
                    .isError(true)
                    .build();
        }

        Object rawItems = arguments.get("items");
        if (!(rawItems instanceof List<?> itemsList) || itemsList.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Parameter 'items' is required and must not be empty.")
                    .isError(true)
                    .build();
        }

        List<OrderItemRequest> reqItems = new ArrayList<>();
        for (Object itemObj : itemsList) {
            if (!(itemObj instanceof Map<?, ?> itemMap)) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Each item must be an object with 'sku' and 'quantity'.")
                        .isError(true)
                        .build();
            }

            Object rawSku = itemMap.get("sku");
            if (rawSku == null || rawSku.toString().isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Item 'sku' is required.")
                        .isError(true)
                        .build();
            }
            String sku = rawSku.toString().trim();

            Object rawQty = itemMap.get("quantity");
            Integer qty = parseInteger(rawQty);
            if (qty == null || qty < 1) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Item 'quantity' must be at least 1 for SKU '" + sku + "'.")
                        .isError(true)
                        .build();
            }

            reqItems.add(new OrderItemRequest(sku, qty));
        }

        Object rawKey = arguments.get("idempotency_key") != null ? arguments.get("idempotency_key") : arguments.get("idempotencyKey");
        String idempotencyKey = rawKey != null ? rawKey.toString().trim() : null;

        try {
            Order order = orderService.placeOrder(customerId, reqItems, idempotencyKey);
            String confirmation = formatOrderPlaced(order);
            return McpSchema.CallToolResult.builder().addTextContent(confirmation).isError(false).build();
        } catch (InsufficientStockException ex) {
            String errorMsg = String.format(
                    "Insufficient stock for product '%s'. Requested: %d, available: %d. Remedy: Reduce order quantity for '%s' to %d or fewer units.",
                    ex.getSku(), ex.getRequestedQuantity(), ex.getAvailableQuantity(),
                    ex.getSku(), ex.getAvailableQuantity()
            );
            return McpSchema.CallToolResult.builder().addTextContent(errorMsg).isError(true).build();
        } catch (ResourceNotFoundException ex) {
            return McpSchema.CallToolResult.builder().addTextContent(ex.getMessage()).isError(true).build();
        } catch (Exception ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error placing order: " + ex.getMessage())
                    .isError(true)
                    .build();
        }
    }

    public McpSchema.CallToolResult listCustomerOrders(Map<String, Object> arguments) {
        if (arguments == null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Arguments are required.")
                    .isError(true)
                    .build();
        }

        Long customerId = parseLong(arguments.get("customer_id") != null ? arguments.get("customer_id") : arguments.get("customerId"));
        if (customerId == null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Parameter 'customer_id' is required.")
                    .isError(true)
                    .build();
        }

        OrderStatus orderStatus = null;
        Object rawStatus = arguments.get("status");
        if (rawStatus != null && !rawStatus.toString().isBlank()) {
            String statusStr = rawStatus.toString().trim().toUpperCase();
            try {
                orderStatus = OrderStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Invalid status '" + rawStatus + "'. Allowed values: [PLACED, CONFIRMED, PARCELED, DELIVERING, DELIVERED, CANCELLED]")
                        .isError(true)
                        .build();
            }
        }

        int page = parseIntegerOrDefault(arguments.get("page"), 0);
        int size = parseIntegerOrDefault(arguments.get("size"), 20);
        if (page < 0) page = 0;
        if (size < 1) size = 20;

        try {
            Page<OrderResponse> ordersPage = orderService.searchOrders(
                    customerId,
                    orderStatus,
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "placedAt"))
            );
            String formatted = formatOrderList(customerId, ordersPage);
            return McpSchema.CallToolResult.builder().addTextContent(formatted).isError(false).build();
        } catch (Exception ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error searching orders: " + ex.getMessage())
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

    private String formatOrderPlaced(Order order) {
        String customerName = order.getCustomer() != null ? order.getCustomer().getFullName() : "Customer";
        List<String> lines = new ArrayList<>();
        lines.add("Order successfully placed!");
        lines.add("- Order Number: " + order.getOrderNumber());
        lines.add("- Status: " + order.getStatus());
        lines.add("- Customer: " + customerName);
        lines.add("- Total Amount: $" + order.getTotalAmount());
        lines.add("- Placed At: " + order.getPlacedAt());

        List<OrderItem> items = order.getItems();
        if (items != null && !items.isEmpty()) {
            lines.add(String.format("- Items (%d):", items.size()));
            for (OrderItem item : items) {
                String sku = item.getProduct() != null ? item.getProduct().getSku() : "N/A";
                String name = item.getProduct() != null ? item.getProduct().getName() : "Item";
                lines.add(String.format("  * [%s] %s x %d @ $%s", sku, name, item.getQuantity(), item.getUnitPrice()));
            }
        }
        return String.join("\n", lines);
    }

    private String formatOrderList(Long customerId, Page<OrderResponse> page) {
        if (page.isEmpty()) {
            return "No orders found for customer ID: " + customerId;
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.format("Found %d order(s) for customer ID %d (showing page %d of %d):",
                page.getTotalElements(), customerId, page.getNumber(), page.getTotalPages()));

        for (OrderResponse o : page.getContent()) {
            int itemCount = o.items() != null ? o.items().size() : 0;
            lines.add(String.format("- [%s] Status: %s, Total: $%s, Items: %d, Placed: %s",
                    o.orderNumber(), o.status(), o.totalAmount(), itemCount, o.placedAt()));
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

    private Long parseLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInteger(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseIntegerOrDefault(Object val, int defaultVal) {
        Integer parsed = parseInteger(val);
        return parsed != null ? parsed : defaultVal;
    }
}
