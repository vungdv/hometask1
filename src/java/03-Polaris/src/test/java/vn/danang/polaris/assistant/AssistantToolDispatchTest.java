package vn.danang.polaris.assistant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.assistant.service.AssistantDraftService;
import vn.danang.polaris.assistant.tool.CancelOrderReviewTool;
import vn.danang.polaris.assistant.tool.GetOrderStatusTool;
import vn.danang.polaris.assistant.tool.GetProductStockTool;
import vn.danang.polaris.assistant.tool.SearchProductsTool;
import vn.danang.polaris.assistant.tool.StageOrderDraftTool;
import vn.danang.polaris.assistant.tool.ToolExecutionResult;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.entity.Customer;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.entity.OrderItem;
import vn.danang.polaris.entity.OrderStatus;
import vn.danang.polaris.entity.Product;
import vn.danang.polaris.service.OrderService;
import vn.danang.polaris.service.ProductService;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

class AssistantToolDispatchTest {

    private ProductService productService;
    private AssistantDraftService draftService;
    private OrderService orderService;

    private SearchProductsTool searchProductsTool;
    private GetProductStockTool getProductStockTool;
    private StageOrderDraftTool stageOrderDraftTool;
    private GetOrderStatusTool getOrderStatusTool;
    private CancelOrderReviewTool cancelOrderReviewTool;

    private SessionContext defaultContext;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        draftService = mock(AssistantDraftService.class);
        orderService = mock(OrderService.class);

        searchProductsTool = new SearchProductsTool(productService);
        getProductStockTool = new GetProductStockTool(productService);
        stageOrderDraftTool = new StageOrderDraftTool(productService, draftService);
        getOrderStatusTool = new GetOrderStatusTool(orderService);
        cancelOrderReviewTool = new CancelOrderReviewTool(orderService);

        defaultContext = new SessionContext("sess-1", "user-1", 100L, List.of(), "test prompt");
    }

    // ==========================================
    // 1. SearchProductsTool Tests
    // ==========================================

    @Test
    void searchProducts_withMatchingQuery_returnsProductListCardWidget() {
        ProductResponse p = new ProductResponse(
                1L, "NG-EARBUD-01", "Earbuds", "Wireless",
                "Audio", new BigDecimal("49.99"), 15, true, true, null
        );
        when(productService.searchProducts(eq("earbud"), any(), any(), any(), any(), eq(true), eq(PageRequest.of(0, 10))))
                .thenReturn(new PageImpl<>(List.of(p)));

        ToolExecutionResult result = searchProductsTool.execute(Map.of("query", "earbud"), defaultContext);

        assertThat(result.success()).isTrue();
        assertThat(result.widgetType()).isEqualTo("PRODUCT_LIST_CARD");
        assertThat(result.output()).isInstanceOf(List.class);
    }

    @Test
    void searchProducts_withNoMatches_returnsEmptyList() {
        when(productService.searchProducts(any(), any(), any(), any(), any(), eq(true), any()))
                .thenReturn(new PageImpl<>(List.of()));

        ToolExecutionResult result = searchProductsTool.execute(Map.of("query", "nonexistent"), defaultContext);

        assertThat(result.success()).isTrue();
        assertThat(result.widgetType()).isNull();
        assertThat(result.output()).isEqualTo(List.of());
    }

    // ==========================================
    // 2. GetProductStockTool Tests
    // ==========================================

    @Test
    void getProductStock_withValidSku_returnsProductCardWidget() {
        ProductResponse p = new ProductResponse(
                1L, "NG-EARBUD-01", "Earbuds", "Wireless",
                "Audio", new BigDecimal("49.99"), 22, true, true, null
        );
        when(productService.getProductBySku("NG-EARBUD-01")).thenReturn(p);

        ToolExecutionResult result = getProductStockTool.execute(Map.of("sku", "NG-EARBUD-01"), defaultContext);

        assertThat(result.success()).isTrue();
        assertThat(result.widgetType()).isEqualTo("PRODUCT_CARD");
        assertThat(result.output()).isEqualTo(p);
    }

    @Test
    void getProductStock_withMissingSku_returnsFailure() {
        ToolExecutionResult result = getProductStockTool.execute(Map.of(), defaultContext);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("SKU parameter is required");
    }

    @Test
    void getProductStock_whenProductNotFound_returnsFailure() {
        when(productService.getProductBySku("INVALID")).thenThrow(new ResourceNotFoundException("Product not found"));

        ToolExecutionResult result = getProductStockTool.execute(Map.of("sku", "INVALID"), defaultContext);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Product not found");
    }

    // ==========================================
    // 3. StageOrderDraftTool Tests
    // ==========================================

    @Test
    void stageOrderDraft_missingSessionContext_returnsFailure() {
        ToolExecutionResult result = stageOrderDraftTool.execute(Map.of("items", List.of()), null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Active session ID is required");
    }

    @Test
    void stageOrderDraft_insufficientStock_returnsProblemCardWithRemedy() {
        ProductResponse earbud = new ProductResponse(
                1L, "NG-EARBUD-01", "Earbuds", "Wireless",
                "Audio", new BigDecimal("49.99"), 2, true, true, null
        );
        when(productService.getProductBySku("NG-EARBUD-01")).thenReturn(earbud);

        Map<String, Object> args = Map.of(
                "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 5))
        );

        ToolExecutionResult result = stageOrderDraftTool.execute(args, defaultContext);

        assertThat(result.success()).isFalse();
        assertThat(result.widgetType()).isEqualTo("PROBLEM_CARD");
        assertThat(result.widgetPayload()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> problem = (Map<String, Object>) result.widgetPayload();
        assertThat(problem.get("title")).isEqualTo("Insufficient Stock");
        assertThat(problem.get("status")).isEqualTo(400);
        assertThat(problem.get("requested_quantity")).isEqualTo(5);
        assertThat(problem.get("available_quantity")).isEqualTo(2);
        assertThat(problem.get("remedy").toString()).contains("2 or fewer");
    }

    @Test
    void stageOrderDraft_sufficientStock_stagesDraftWith15MinTtl() {
        ProductResponse earbud = new ProductResponse(
                1L, "NG-EARBUD-01", "Earbuds", "Wireless",
                "Audio", new BigDecimal("49.99"), 50, true, true, null
        );
        when(productService.getProductBySku("NG-EARBUD-01")).thenReturn(earbud);

        AssistantSession session = new AssistantSession();
        session.setId("sess-1");

        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId("draft-uuid-123");
        draft.setSession(session);
        draft.setCustomerId(100L);
        draft.setStatus(DraftStatus.WAITING_CONFIRMATION);
        draft.setTotalAmount(new BigDecimal("99.98"));
        draft.setExpiresAt(Instant.now().plusSeconds(900));

        when(draftService.stageDraft(eq("sess-1"), eq(100L), any(), eq(new BigDecimal("99.98")), eq(15)))
                .thenReturn(draft);

        Map<String, Object> args = Map.of(
                "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 2))
        );

        ToolExecutionResult result = stageOrderDraftTool.execute(args, defaultContext);

        assertThat(result.success()).isTrue();
        assertThat(result.draftPayload()).isNotNull();
        assertThat(result.draftPayload().id()).isEqualTo("draft-uuid-123");
        assertThat(result.draftPayload().status()).isEqualTo("WAITING_CONFIRMATION");
        verify(draftService).stageDraft(eq("sess-1"), eq(100L), any(), eq(new BigDecimal("99.98")), eq(15));
    }

    // ==========================================
    // 4. GetOrderStatusTool Tests
    // ==========================================

    @Test
    void getOrderStatus_withValidOrder_returnsOrderStatusCard() {
        Customer customer = new Customer();
        customer.setId(100L);
        customer.setFullName("John Doe");

        Order order = new Order();
        order.setId(10L);
        order.setOrderNumber("ORD-1001");
        order.setStatus(OrderStatus.CONFIRMED);
        order.setTotalAmount(new BigDecimal("120.00"));
        order.setPlacedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        order.setCustomer(customer);
        order.setItems(new ArrayList<>());

        when(orderService.getOrderStatus("ORD-1001")).thenReturn(order);

        ToolExecutionResult result = getOrderStatusTool.execute(Map.of("orderNumber", "ORD-1001"), defaultContext);

        assertThat(result.success()).isTrue();
        assertThat(result.widgetType()).isEqualTo("ORDER_STATUS_CARD");
    }

    @Test
    void getOrderStatus_whenOrderNotFound_returnsFailure() {
        when(orderService.getOrderStatus("ORD-9999")).thenThrow(new ResourceNotFoundException("Order not found"));

        ToolExecutionResult result = getOrderStatusTool.execute(Map.of("orderNumber", "ORD-9999"), defaultContext);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Order not found");
    }

    // ==========================================
    // 5. CancelOrderReviewTool Tests
    // ==========================================

    @Test
    void cancelOrderReview_whenOrderIsPlaced_returnsCancellableReviewWithRestockNotice() {
        Product p = new Product();
        p.setSku("NG-EARBUD-01");
        p.setName("Wireless Earbuds");

        OrderItem item = new OrderItem();
        item.setProduct(p);
        item.setQuantity(2);

        Customer customer = new Customer();
        customer.setId(100L);

        Order order = new Order();
        order.setId(10L);
        order.setOrderNumber("ORD-1001");
        order.setStatus(OrderStatus.PLACED);
        order.setTotalAmount(new BigDecimal("99.98"));
        order.setCustomer(customer);
        order.setItems(List.of(item));

        when(orderService.getOrderStatus("ORD-1001")).thenReturn(order);

        ToolExecutionResult result = cancelOrderReviewTool.execute(Map.of("orderNumber", "ORD-1001"), defaultContext);

        assertThat(result.success()).isTrue();
        assertThat(result.widgetType()).isEqualTo("CANCELLATION_REVIEW_CARD");
        assertThat(result.widgetPayload()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.widgetPayload();
        assertThat(payload.get("orderNumber")).isEqualTo("ORD-1001");
        assertThat(payload.get("currentStatus")).isEqualTo("PLACED");
        assertThat(payload.get("cancellable")).isEqualTo(true);
        assertThat(payload.get("restockNotice").toString()).contains("return 2x NG-EARBUD-01 to inventory");
    }

    @Test
    void cancelOrderReview_whenOrderIsShipped_returnsProblemCard409() {
        Customer customer = new Customer();
        customer.setId(100L);

        Order order = new Order();
        order.setId(10L);
        order.setOrderNumber("ORD-1002");
        order.setStatus(OrderStatus.DELIVERED);
        order.setTotalAmount(new BigDecimal("99.98"));
        order.setCustomer(customer);
        order.setItems(List.of());

        when(orderService.getOrderStatus("ORD-1002")).thenReturn(order);

        ToolExecutionResult result = cancelOrderReviewTool.execute(Map.of("orderNumber", "ORD-1002"), defaultContext);

        assertThat(result.success()).isFalse();
        assertThat(result.widgetType()).isEqualTo("PROBLEM_CARD");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.widgetPayload();
        assertThat(payload.get("title")).isEqualTo("Order State Conflict");
        assertThat(payload.get("status")).isEqualTo(409);
        assertThat(payload.get("remedy").toString()).contains("return/refund workflow");
    }
}
