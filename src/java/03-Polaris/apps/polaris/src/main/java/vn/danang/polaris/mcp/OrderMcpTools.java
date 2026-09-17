package vn.danang.polaris.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import vn.danang.polaris.order.dto.CustomerSummaryResponse;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.dto.OrderResponse;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.entity.OrderItem;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.order.service.CustomerService;
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
    public static final String TOOL_SEARCH_CUSTOMERS_BY_NAME = "search_customers_by_name";

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
              "description": "Unique numeric ID of the customer placing the order (use this or customer_name)"
            },
            "customer_name": {
              "type": "string",
              "description": "Customer name for fuzzy lookup — partial, case-insensitive, typo-tolerant. Used when customer_id is absent. Returns disambiguation list if multiple matches found."
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
          "required": ["items"]
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

    private static final String SEARCH_CUSTOMERS_BY_NAME_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "Customer name to search — partial or full, case-insensitive, typo-tolerant"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of candidates to return (default: 5, max: 20)"
            }
          },
          "required": ["name"]
        }
        """;

    private final OrderService orderService;
    private final CustomerService customerService;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public OrderMcpTools(
            OrderService orderService,
            CustomerService customerService,
            ObjectProvider<Tracer> tracerProvider) {
        this.orderService = orderService;
        this.customerService = customerService;
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
    }

    public OrderMcpTools(OrderService orderService, CustomerService customerService) {
        this(orderService, customerService, null);
    }

    public OrderMcpTools(OrderService orderService, ObjectProvider<Tracer> tracerProvider) {
        this(orderService, null, tracerProvider);
    }

    public OrderMcpTools(OrderService orderService) {
        this(orderService, null, null);
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
                .description("Place a new multi-item order for a customer. Accepts customer_id or customer_name (fuzzy match). "
                        + "Returns a disambiguation candidate list if multiple name matches are found.")
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

    public McpSchema.Tool getSearchCustomersByNameTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder()
                .name(TOOL_SEARCH_CUSTOMERS_BY_NAME)
                .description("Search for customers by partial or fuzzy name match. Returns ranked candidates with id, name, "
                        + "and email for order placement disambiguation.")
                .inputSchema(jsonMapper, SEARCH_CUSTOMERS_BY_NAME_SCHEMA)
                .build();
    }

    public McpSchema.Tool getSearchCustomersByNameTool() {
        return getSearchCustomersByNameTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    private McpSchema.CallToolResult executeWithSpan(String toolName, Supplier<McpSchema.CallToolResult> execution) {
        if (this.tracer == null) {
            return execution.get();
        }

        String spanName = "mcp.server.tool_call %s".formatted(toolName);
        Span span = this.tracer.nextSpan().name(spanName);
        span.tag("mcp.tool.name", toolName);
        span.tag("mcp.server", "polaris-mcp");
        span.tag("mcp.category", "order");
        span.start();

        try (Tracer.SpanInScope ws = this.tracer.withSpan(span)) {
            McpSchema.CallToolResult result = execution.get();
            if (result != null && Boolean.TRUE.equals(result.isError())) {
                span.tag("error", "true");
            }
            return result;
        } catch (Exception ex) {
            span.error(ex);
            span.tag("error", "true");
            throw ex;
        } finally {
            span.end();
        }
    }

    public McpSchema.CallToolResult getOrderStatus(Map<String, Object> arguments) {
        String orderNumber = getOrderNumber(arguments);
        return getOrderStatus(orderNumber);
    }

    @Transactional(readOnly = true)
    public McpSchema.CallToolResult getOrderStatus(String orderNumber) {
        return executeWithSpan(TOOL_GET_ORDER_STATUS, () -> {
            if (orderNumber == null || orderNumber.isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Parameter 'order_number' is required.")
                        .isError(true)
                        .build();
            }
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
        });
    }

    public McpSchema.CallToolResult getOrderDetails(Map<String, Object> arguments) {
        return executeWithSpan(TOOL_GET_ORDER_DETAILS, () -> {
            String orderNumber = getOrderNumber(arguments);
            if (orderNumber == null || orderNumber.isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Parameter 'order_number' is required.")
                        .isError(true)
                        .build();
            }
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
        });
    }

    public McpSchema.CallToolResult placeOrder(Map<String, Object> arguments) {
        return executeWithSpan(TOOL_PLACE_ORDER, () -> {
            if (arguments == null) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Arguments are required.")
                        .isError(true)
                        .build();
            }

            // Resolve customer: prefer customer_id, fall back to customer_name fuzzy lookup
            Long customerId = parseLong(arguments.get("customer_id") != null ? arguments.get("customer_id") : arguments.get("customerId"));

            if (customerId == null) {
                Object rawCustomerName = arguments.get("customer_name") != null ? arguments.get("customer_name") : arguments.get("customerName");
                String customerName = rawCustomerName != null ? rawCustomerName.toString().trim() : null;

                if (customerName == null || customerName.isBlank()) {
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("Either 'customer_id' or 'customer_name' is required to identify the customer.")
                            .isError(true)
                            .build();
                }

                // Fuzzy name lookup
                List<CustomerSummaryResponse> candidates;
                try {
                    candidates = customerService.searchByName(customerName, 10);
                } catch (Exception ex) {
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("Error searching customer '" + customerName + "': " + ex.getMessage())
                            .isError(true)
                            .build();
                }

                if (candidates.isEmpty()) {
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("No customer found matching '" + customerName + "'. "
                                    + "Please check the name spelling and retry, or provide the customer_id directly.")
                            .isError(true)
                            .build();
                }

                if (candidates.size() > 1) {
                    // Disambiguation: return candidate list without placing the order
                    List<String> lines = new ArrayList<>();
                    lines.add("Multiple customers found for '" + customerName + "'. Please confirm by specifying customer_id:");
                    for (int i = 0; i < candidates.size(); i++) {
                        CustomerSummaryResponse c = candidates.get(i);
                        lines.add(String.format("%d. [ID: %d] %s | %s", i + 1, c.id(), c.fullName(), c.email()));
                    }
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(String.join("\n", lines))
                            .isError(false)
                            .build();
                }

                // Exactly 1 match — proceed with resolved customer
                customerId = candidates.get(0).id();
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
        });
    }

    public McpSchema.CallToolResult listCustomerOrders(Map<String, Object> arguments) {
        return executeWithSpan(TOOL_LIST_CUSTOMER_ORDERS, () -> {
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
        });
    }

    public McpSchema.CallToolResult cancelOrder(Map<String, Object> arguments) {
        String orderNumber = getOrderNumber(arguments);
        return cancelOrder(orderNumber);
    }

    public McpSchema.CallToolResult cancelOrder(String orderNumber) {
        return executeWithSpan(TOOL_CANCEL_ORDER, () -> {
            if (orderNumber == null || orderNumber.isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Parameter 'order_number' is required.")
                        .isError(true)
                        .build();
            }
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
        });
    }

    public McpSchema.CallToolResult searchCustomersByName(Map<String, Object> arguments) {
        return executeWithSpan(TOOL_SEARCH_CUSTOMERS_BY_NAME, () -> {
            if (arguments == null) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Arguments are required.")
                        .isError(true)
                        .build();
            }

            Object rawName = arguments.get("name");
            String name = rawName != null ? rawName.toString().trim() : null;
            if (name == null || name.isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Parameter 'name' is required and must not be blank.")
                        .isError(true)
                        .build();
            }

            int limit = parseIntegerOrDefault(arguments.get("limit"), 5);
            limit = Math.max(1, Math.min(limit, 20));

            try {
                List<CustomerSummaryResponse> customers = customerService.searchByName(name, limit);
                if (customers.isEmpty()) {
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("No customers found matching '" + name + "'.")
                            .isError(false)
                            .build();
                }

                List<String> lines = new ArrayList<>();
                lines.add("Found " + customers.size() + " customer(s) matching '" + name + "':");
                for (int i = 0; i < customers.size(); i++) {
                    CustomerSummaryResponse c = customers.get(i);
                    lines.add(String.format("%d. [ID: %d] %s | %s", i + 1, c.id(), c.fullName(), c.email()));
                }
                return McpSchema.CallToolResult.builder()
                        .addTextContent(String.join("\n", lines))
                        .isError(false)
                        .build();
            } catch (Exception ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Error searching customers: " + ex.getMessage())
                        .isError(true)
                        .build();
            }
        });
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
