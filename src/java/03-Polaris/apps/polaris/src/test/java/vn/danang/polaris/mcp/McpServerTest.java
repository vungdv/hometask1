package vn.danang.polaris.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class McpServerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMcpTools productMcpTools;

    @Autowired
    private OrderMcpTools orderMcpTools;

    @Autowired
    private McpSyncServer mcpSyncServer;

    @Autowired
    private HttpServletSseServerTransportProvider transport;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        orderRepository.findByOrderNumber("ORD-1001").ifPresent(order -> {
            order.setStatus(OrderStatus.PLACED);
            orderRepository.save(order);
        });
    }

    @Nested
    @DisplayName("ProductMcpTools Unit & Integration Tests")
    class ProductToolsTests {

        @Test
        @DisplayName("Verify search_available_products tool schema contract")
        void searchAvailableProducts_schemaContract() {
            McpSchema.Tool tool = productMcpTools.getSearchProductsTool();
            assertThat(tool.name()).isEqualTo("search_available_products");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull();
            assertThat(properties).containsKey("query");
            assertThat(properties).containsKey("category");
            assertThat(properties).containsKey("min_price");
            assertThat(properties).containsKey("max_price");
            assertThat(properties).containsKey("available_only");
            assertThat(properties).containsKey("page");
            assertThat(properties).containsKey("size");
            assertThat(properties).containsKey("sort");
        }

        @Test
        @DisplayName("Verify get_product_by_sku tool schema contract")
        void getProductBySku_schemaContract() {
            McpSchema.Tool tool = productMcpTools.getProductBySkuTool();
            assertThat(tool.name()).isEqualTo("get_product_by_sku");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull().containsKey("sku");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("sku");
        }

        @Test
        @DisplayName("search_available_products with default arguments returns seeded products")
        void searchAvailableProducts_defaultArgs() {
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(Map.of());
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).isNotEmpty();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Found ");
            assertThat(text).contains("product(s):");
            assertThat(text).contains("NG-EARBUD-01");
        }

        @Test
        @DisplayName("search_available_products with keyword query filters correctly")
        void searchAvailableProducts_keywordQuery() {
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(Map.of("query", "Earbuds"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Nova Wireless Earbuds");
            assertThat(text).contains("NG-EARBUD-01");
        }

        @Test
        @DisplayName("search_available_products with price range filters correctly")
        void searchAvailableProducts_priceRange() {
            Map<String, Object> args = new HashMap<>();
            args.put("min_price", 30.00);
            args.put("max_price", 50.00);
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("NG-EARBUD-01"); // 49.90
            assertThat(text).contains("NG-SPEAKER-01"); // 39.90
            assertThat(text).doesNotContain("NG-CASE-01"); // 14.90
        }

        @Test
        @DisplayName("search_available_products with sort parameter applies sort order")
        void searchAvailableProducts_withSort() {
            Map<String, Object> args = Map.of("sort", "price,desc");
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Found ");
        }

        @Test
        @DisplayName("search_available_products when no products match returns clean empty message")
        void searchAvailableProducts_emptyResult() {
            Map<String, Object> args = Map.of("query", "DEFINITELY_NON_EXISTENT_PRODUCT_12345");
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).isEqualTo("No products found matching the specified criteria.");
        }

        @Test
        @DisplayName("search_available_products with invalid page number returns error result")
        void searchAvailableProducts_invalidPage() {
            Map<String, Object> args = Map.of("page", -5);
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Error searching products");
        }

        @Test
        @DisplayName("search_available_products with invalid page size returns error result")
        void searchAvailableProducts_invalidSize() {
            Map<String, Object> args = Map.of("size", 500);
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Error searching products");
        }

        @Test
        @DisplayName("search_available_products with unsupported sort property returns error result")
        void searchAvailableProducts_invalidSort() {
            Map<String, Object> args = Map.of("sort", "unsupported_field,asc");
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Error searching products");
        }

        @Test
        @DisplayName("get_product_by_sku with valid SKU returns product details and stock status")
        void getProductBySku_validSku() {
            McpSchema.CallToolResult result = productMcpTools.getProductBySku(Map.of("sku", "NG-EARBUD-01"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Product Details for Nova Wireless Earbuds");
            assertThat(text).contains("- SKU: NG-EARBUD-01");
            assertThat(text).contains("- Price: $49.90");
            assertThat(text).contains("In Stock (120 available)");
        }

        @Test
        @DisplayName("get_product_by_sku with non-existent SKU returns clean error message")
        void getProductBySku_nonExistentSku() {
            McpSchema.CallToolResult result = productMcpTools.getProductBySku(Map.of("sku", "NONEXISTENT-999"));
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Product not found with SKU: NONEXISTENT-999");
        }

        @Test
        @DisplayName("get_product_by_sku with missing SKU returns error message")
        void getProductBySku_missingSku() {
            McpSchema.CallToolResult result = productMcpTools.getProductBySku(Map.of());
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'sku' is required.");
        }
    }

    @Nested
    @DisplayName("OrderMcpTools Unit & Integration Tests")
    class OrderToolsTests {

        @Test
        @DisplayName("Verify get_order_status tool schema contract")
        void getOrderStatus_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getOrderStatusTool();
            assertThat(tool.name()).isEqualTo("get_order_status");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull().containsKey("order_number");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("order_number");
        }

        @Test
        @DisplayName("Verify cancel_order tool schema contract")
        void cancelOrder_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getCancelOrderTool();
            assertThat(tool.name()).isEqualTo("cancel_order");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull().containsKey("order_number");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("order_number");
        }

        @Test
        @DisplayName("get_order_status for seeded order returns status, customer, and items")
        void getOrderStatus_seededOrder() {
            McpSchema.CallToolResult result = orderMcpTools.getOrderStatus(Map.of("order_number", "ORD-1002"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order Status for ORD-1002:");
            assertThat(text).contains("- Status: CONFIRMED");
            assertThat(text).contains("- Customer: Alice Tran");
            assertThat(text).contains("- Total Amount: $89.90");
            assertThat(text).contains("- Items");
        }

        @Test
        @DisplayName("get_order_status with non-existent order returns clean error message")
        void getOrderStatus_nonExistentOrder() {
            McpSchema.CallToolResult result = orderMcpTools.getOrderStatus(Map.of("order_number", "ORD-9999"));
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order not found with order number: ORD-9999");
        }

        @Test
        @DisplayName("get_order_status with missing order_number returns required message")
        void getOrderStatus_missingOrderNumber() {
            McpSchema.CallToolResult result = orderMcpTools.getOrderStatus(Map.of());
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'order_number' is required.");
        }

        @Test
        @DisplayName("cancel_order for PLACED order transitions order to CANCELLED")
        void cancelOrder_placedOrder_success() {
            McpSchema.CallToolResult result = orderMcpTools.cancelOrder(Map.of("order_number", "ORD-1001"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order ORD-1001 has been successfully cancelled.");
            assertThat(text).contains("- Status: CANCELLED");
        }

        @Test
        @DisplayName("cancel_order for DELIVERED order returns state conflict error")
        void cancelOrder_deliveredOrder_conflict() {
            McpSchema.CallToolResult result = orderMcpTools.cancelOrder(Map.of("order_number", "ORD-1005"));
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Cannot cancel order: Order ORD-1005 cannot be cancelled — current status is DELIVERED");
        }

        @Test
        @DisplayName("cancel_order for non-existent order returns not found error")
        void cancelOrder_nonExistentOrder() {
            McpSchema.CallToolResult result = orderMcpTools.cancelOrder(Map.of("order_number", "ORD-9999"));
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order not found with order number: ORD-9999");
        }

        @Test
        @DisplayName("Verify get_order_details tool schema contract")
        void getOrderDetails_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getOrderDetailsTool();
            assertThat(tool.name()).isEqualTo("get_order_details");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull().containsKey("order_number");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("order_number");
        }

        @Test
        @DisplayName("Verify place_order tool schema contract")
        void placeOrder_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getPlaceOrderTool();
            assertThat(tool.name()).isEqualTo("place_order");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull();
            assertThat(properties).containsKey("customer_id");
            assertThat(properties).containsKey("items");
            assertThat(properties).containsKey("idempotency_key");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("customer_id", "items");
        }

        @Test
        @DisplayName("Verify list_customer_orders tool schema contract")
        void listCustomerOrders_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getListCustomerOrdersTool();
            assertThat(tool.name()).isEqualTo("list_customer_orders");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull();
            assertThat(properties).containsKey("customer_id");
            assertThat(properties).containsKey("status");
            assertThat(properties).containsKey("page");
            assertThat(properties).containsKey("size");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("customer_id");
        }

        @Test
        @DisplayName("getOrderDetails for seeded order returns status, customer, and items")
        void getOrderDetails_seededOrder() {
            McpSchema.CallToolResult result = orderMcpTools.getOrderDetails(Map.of("order_number", "ORD-1002"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order Status for ORD-1002:");
            assertThat(text).contains("- Status: CONFIRMED");
            assertThat(text).contains("- Customer: Alice Tran");
            assertThat(text).contains("- Total Amount: $89.90");
        }

        @Test
        @DisplayName("place_order with valid items creates order successfully")
        void placeOrder_success() {
            Map<String, Object> item1 = Map.of("sku", "NG-EARBUD-01", "quantity", 1);
            Map<String, Object> item2 = Map.of("sku", "NG-CHARGER-01", "quantity", 2);
            Map<String, Object> args = Map.of(
                    "customer_id", 1,
                    "items", List.of(item1, item2),
                    "idempotency_key", "mcp-place-test-1"
            );

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order successfully placed!");
            assertThat(text).contains("- Status: PLACED");
            assertThat(text).contains("- Customer: Alice Tran");
            assertThat(text).contains("- Total Amount: $99.70");
            assertThat(text).contains("NG-EARBUD-01");
            assertThat(text).contains("NG-CHARGER-01");
        }

        @Test
        @DisplayName("place_order with insufficient stock returns actionable error remedy")
        void placeOrder_insufficientStock_returnsRemedy() {
            Map<String, Object> item = Map.of("sku", "NG-WATCH-01", "quantity", 9999);
            Map<String, Object> args = Map.of("customer_id", 1, "items", List.of(item));

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Insufficient stock for product 'NG-WATCH-01'");
            assertThat(text).contains("Remedy: Reduce order quantity for 'NG-WATCH-01'");
        }

        @Test
        @DisplayName("place_order with missing customer_id returns error")
        void placeOrder_missingCustomerId() {
            Map<String, Object> item = Map.of("sku", "NG-EARBUD-01", "quantity", 1);
            Map<String, Object> args = Map.of("items", List.of(item));

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'customer_id' is required.");
        }

        @Test
        @DisplayName("place_order with empty items returns error")
        void placeOrder_emptyItems() {
            Map<String, Object> args = Map.of("customer_id", 1, "items", List.of());

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'items' is required and must not be empty.");
        }

        @Test
        @DisplayName("list_customer_orders for customer with orders returns formatted list")
        void listCustomerOrders_seededCustomer() {
            Map<String, Object> args = Map.of("customer_id", 1);
            McpSchema.CallToolResult result = orderMcpTools.listCustomerOrders(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Found ");
            assertThat(text).contains("order(s) for customer ID 1");
            assertThat(text).contains("ORD-1001");
            assertThat(text).contains("ORD-1002");
        }

        @Test
        @DisplayName("list_customer_orders with missing customer_id returns error")
        void listCustomerOrders_missingCustomerId() {
            McpSchema.CallToolResult result = orderMcpTools.listCustomerOrders(Map.of());
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'customer_id' is required.");
        }

        @Test
        @DisplayName("cancel_order with missing order_number returns required message")
        void cancelOrder_missingOrderNumber() {
            McpSchema.CallToolResult result = orderMcpTools.cancelOrder(Map.of());
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'order_number' is required.");
        }
    }

    @Nested
    @DisplayName("McpServerConfig & Wiring Tests")
    class ServerConfigWiringTests {

        @Test
        @DisplayName("Verify McpSyncServer bean is instantiated")
        void mcpSyncServer_isNotNull() {
            assertThat(mcpSyncServer).isNotNull();
        }

        @Test
        @DisplayName("Verify HttpServletSseServerTransportProvider bean is instantiated")
        void transport_isNotNull() {
            assertThat(transport).isNotNull();
        }

        @Test
        @DisplayName("Security: unauthenticated GET /mcp/sse returns 401 Unauthorized")
        void mcpSse_unauthenticated_returnsUnauthorized() throws Exception {
            mockMvc.perform(get("/mcp/sse"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Security: authenticated GET /mcp/sse is authorized")
        void mcpSse_authenticated_isAuthorized() throws Exception {
            mockMvc.perform(get("/mcp/sse").with(JwtMockFactory.user()))
                    .andExpect(result -> {
                        int statusCode = result.getResponse().getStatus();
                        assertThat(statusCode).isNotEqualTo(401);
                        assertThat(statusCode).isNotEqualTo(403);
                    });
        }

        @Test
        @DisplayName("Security: unauthenticated POST /mcp/message returns 401 Unauthorized")
        void mcpMessage_unauthenticated_returnsUnauthorized() throws Exception {
            mockMvc.perform(post("/mcp/message")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Security: authenticated POST /mcp/message is authorized")
        void mcpMessage_authenticated_isAuthorized() throws Exception {
            mockMvc.perform(post("/mcp/message")
                            .with(JwtMockFactory.user())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}"))
                    .andExpect(result -> {
                        int statusCode = result.getResponse().getStatus();
                        assertThat(statusCode).isNotEqualTo(401);
                        assertThat(statusCode).isNotEqualTo(403);
                    });
        }
    }
}
