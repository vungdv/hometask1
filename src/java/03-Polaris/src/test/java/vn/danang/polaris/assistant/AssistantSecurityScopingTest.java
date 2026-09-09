package vn.danang.polaris.assistant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import vn.danang.polaris.assistant.dto.AssistantDraftResponse;
import vn.danang.polaris.assistant.engine.AgencyOrchestrator;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.SessionStatus;
import vn.danang.polaris.assistant.model.AssistantModelClient;
import vn.danang.polaris.assistant.model.ModelStreamListener;
import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.security.AssistantSecurityScoper;
import vn.danang.polaris.assistant.service.AssistantDraftService;
import vn.danang.polaris.assistant.tool.AssistantToolRegistry;
import vn.danang.polaris.assistant.tool.CancelOrderReviewTool;
import vn.danang.polaris.assistant.tool.GetOrderStatusTool;
import vn.danang.polaris.assistant.tool.StageOrderDraftTool;
import vn.danang.polaris.assistant.tool.ToolExecutionResult;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.entity.Customer;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.entity.OrderStatus;
import vn.danang.polaris.repository.CustomerRepository;
import vn.danang.polaris.service.OrderService;
import vn.danang.polaris.service.ProductService;

class AssistantSecurityScopingTest {

    private CustomerRepository customerRepository;
    private AssistantSecurityScoper securityScoper;
    private OrderService orderService;
    private ProductService productService;
    private AssistantDraftService draftService;

    private GetOrderStatusTool getOrderStatusTool;
    private CancelOrderReviewTool cancelOrderReviewTool;
    private StageOrderDraftTool stageOrderDraftTool;

    private Customer customer1;
    private Customer customer2;
    private Order orderCustomer1;
    private Order orderCustomer2;

    @BeforeEach
    void setUp() {
        customerRepository = mock(CustomerRepository.class);
        securityScoper = new AssistantSecurityScoper(customerRepository);
        orderService = mock(OrderService.class);
        productService = mock(ProductService.class);
        draftService = mock(AssistantDraftService.class);

        getOrderStatusTool = new GetOrderStatusTool(orderService, securityScoper);
        cancelOrderReviewTool = new CancelOrderReviewTool(orderService, securityScoper);
        stageOrderDraftTool = new StageOrderDraftTool(productService, draftService, securityScoper);

        customer1 = new Customer();
        customer1.setId(101L);
        customer1.setFullName("Customer One");

        customer2 = new Customer();
        customer2.setId(202L);
        customer2.setFullName("Customer Two");

        orderCustomer1 = new Order();
        orderCustomer1.setId(1L);
        orderCustomer1.setOrderNumber("ORD-101");
        orderCustomer1.setCustomer(customer1);
        orderCustomer1.setStatus(OrderStatus.PLACED);
        orderCustomer1.setTotalAmount(new BigDecimal("50.00"));
        orderCustomer1.setItems(new ArrayList<>());

        orderCustomer2 = new Order();
        orderCustomer2.setId(2L);
        orderCustomer2.setOrderNumber("ORD-202");
        orderCustomer2.setCustomer(customer2);
        orderCustomer2.setStatus(OrderStatus.PLACED);
        orderCustomer2.setTotalAmount(new BigDecimal("99.00"));
        orderCustomer2.setItems(new ArrayList<>());

        when(orderService.getOrderStatus("ORD-101")).thenReturn(orderCustomer1);
        when(orderService.getOrderStatus("ORD-202")).thenReturn(orderCustomer2);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // 1. Retail Shopper (ROLE_USER) Anti-IDOR Scoping
    // =========================================================================

    @Test
    void shopper_attemptToViewOtherCustomerOrder_emitsForbiddenProblemCardWithoutLeakingDetails() {
        // Shopper is customer 101L
        SessionContext shopperContext = new SessionContext(
                "sess-1", "user-101", 101L, List.of(), "check ORD-202", null, false
        );

        // Attempt to query order belonging to customer 202L
        ToolExecutionResult result = getOrderStatusTool.execute(
                Map.of("orderNumber", "ORD-202"), shopperContext
        );

        assertThat(result.success()).isFalse();
        assertThat(result.widgetType()).isEqualTo("PROBLEM_CARD");
        assertThat(result.widgetPayload()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) result.widgetPayload();
        assertThat(card.get("title")).isEqualTo("Forbidden");
        assertThat(card.get("status")).isEqualTo(403);
        assertThat(card.get("type")).isEqualTo("https://polaris.local/errors/forbidden");
        assertThat(card.get("invalid_param")).isEqualTo("orderNumber");
        assertThat(card.get("received")).isEqualTo("ORD-202");
        assertThat(card.get("detail").toString()).doesNotContain("Customer Two");
        assertThat(card.get("detail").toString()).doesNotContain("99.00");
        assertThat(card.get("remedy").toString()).contains("belonging to your account");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) card.get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("action")).isEqualTo("view_my_orders");
    }

    @Test
    void shopper_attemptToCancelOtherCustomerOrder_emitsForbiddenProblemCard() {
        SessionContext shopperContext = new SessionContext(
                "sess-1", "user-101", 101L, List.of(), "cancel ORD-202", null, false
        );

        ToolExecutionResult result = cancelOrderReviewTool.execute(
                Map.of("orderNumber", "ORD-202"), shopperContext
        );

        assertThat(result.success()).isFalse();
        assertThat(result.widgetType()).isEqualTo("PROBLEM_CARD");

        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) result.widgetPayload();
        assertThat(card.get("status")).isEqualTo(403);
        assertThat(card.get("title")).isEqualTo("Forbidden");
        assertThat(card.get("detail").toString()).doesNotContain("Customer Two");
        assertThat(card.get("detail").toString()).doesNotContain("99.00");
    }

    @Test
    void shopper_viewOwnOrder_succeeds() {
        SessionContext shopperContext = new SessionContext(
                "sess-1", "user-101", 101L, List.of(), "check ORD-101", null, false
        );

        ToolExecutionResult result = getOrderStatusTool.execute(
                Map.of("orderNumber", "ORD-101"), shopperContext
        );

        assertThat(result.success()).isTrue();
        assertThat(result.widgetType()).isEqualTo("ORDER_STATUS_CARD");
    }

    @Test
    void shopper_cancelOwnOrder_succeeds() {
        SessionContext shopperContext = new SessionContext(
                "sess-1", "user-101", 101L, List.of(), "cancel ORD-101", null, false
        );

        ToolExecutionResult result = cancelOrderReviewTool.execute(
                Map.of("orderNumber", "ORD-101"), shopperContext
        );

        assertThat(result.success()).isTrue();
        assertThat(result.widgetType()).isEqualTo("CANCELLATION_REVIEW_CARD");
    }

    @Test
    void shopper_attemptToStageDraftForOtherCustomer_emitsForbiddenProblemCard() {
        SessionContext shopperContext = new SessionContext(
                "sess-1", "user-101", 101L, List.of(), "order for other", null, false
        );

        Map<String, Object> args = Map.of(
                "customerId", 202L,
                "items", List.of(Map.of("sku", "NG-CHARGER-01", "quantity", 1))
        );

        ToolExecutionResult result = stageOrderDraftTool.execute(args, shopperContext);

        assertThat(result.success()).isFalse();
        assertThat(result.widgetType()).isEqualTo("PROBLEM_CARD");

        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) result.widgetPayload();
        assertThat(card.get("status")).isEqualTo(403);
        assertThat(card.get("invalid_param")).isEqualTo("customerId");
        assertThat(card.get("received")).isEqualTo(202L);
        assertThat(card.get("detail").toString()).contains("Retail shoppers cannot stage orders on behalf of other customers");
    }

    @Test
    void shopper_stageDraftForSelf_succeeds() {
        SessionContext shopperContext = new SessionContext(
                "sess-1", "user-101", 101L, List.of(), "order for self", null, false
        );

        ProductResponse product = new ProductResponse(
                1L, "NG-CHARGER-01", "Charger", "Fast", "Accessory",
                new BigDecimal("25.00"), 50, true, true, null
        );
        when(productService.getProductBySku("NG-CHARGER-01")).thenReturn(product);

        AssistantSession session = new AssistantSession();
        session.setId("sess-1");
        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId("draft-1");
        draft.setSession(session);
        draft.setCustomerId(101L);
        draft.setStatus(DraftStatus.WAITING_CONFIRMATION);
        draft.setTotalAmount(new BigDecimal("25.00"));
        draft.setExpiresAt(Instant.now().plusSeconds(900));

        when(draftService.stageDraft(eq("sess-1"), eq(101L), any(), eq(new BigDecimal("25.00")), eq(15)))
                .thenReturn(draft);

        Map<String, Object> args = Map.of(
                "items", List.of(Map.of("sku", "NG-CHARGER-01", "quantity", 1))
        );

        ToolExecutionResult result = stageOrderDraftTool.execute(args, shopperContext);

        assertThat(result.success()).isTrue();
        assertThat(result.draftPayload()).isNotNull();
        assertThat(result.draftPayload().customerId()).isEqualTo(101L);
    }

    // =========================================================================
    // 2. Staff / Admin Scoping
    // =========================================================================

    @Test
    void staff_viewAnyCustomerOrder_succeeds() {
        // Staff context: isStaff=true, operatorId="agent-007"
        SessionContext staffContext = new SessionContext(
                "sess-staff", "agent-007", null, List.of(), "lookup ORD-202", "agent-007", true
        );

        ToolExecutionResult result = getOrderStatusTool.execute(
                Map.of("orderNumber", "ORD-202"), staffContext
        );

        assertThat(result.success()).isTrue();
        assertThat(result.widgetType()).isEqualTo("ORDER_STATUS_CARD");
    }

    @Test
    void staff_cancelReviewAnyCustomerOrder_succeeds() {
        SessionContext staffContext = new SessionContext(
                "sess-staff", "agent-007", null, List.of(), "cancel ORD-202", "agent-007", true
        );

        ToolExecutionResult result = cancelOrderReviewTool.execute(
                Map.of("orderNumber", "ORD-202"), staffContext
        );

        assertThat(result.success()).isTrue();
        assertThat(result.widgetType()).isEqualTo("CANCELLATION_REVIEW_CARD");
    }

    @Test
    void staff_stageDraftForValidCustomer_succeeds() {
        SessionContext staffContext = new SessionContext(
                "sess-staff", "agent-007", null, List.of(), "stage for customer 202", "agent-007", true
        );

        when(customerRepository.existsById(202L)).thenReturn(true);

        ProductResponse product = new ProductResponse(
                1L, "NG-CHARGER-01", "Charger", "Fast", "Accessory",
                new BigDecimal("25.00"), 50, true, true, null
        );
        when(productService.getProductBySku("NG-CHARGER-01")).thenReturn(product);

        AssistantSession session = new AssistantSession();
        session.setId("sess-staff");
        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId("draft-staff-1");
        draft.setSession(session);
        draft.setCustomerId(202L);
        draft.setStatus(DraftStatus.WAITING_CONFIRMATION);
        draft.setTotalAmount(new BigDecimal("25.00"));
        draft.setExpiresAt(Instant.now().plusSeconds(900));

        when(draftService.stageDraft(eq("sess-staff"), eq(202L), any(), eq(new BigDecimal("25.00")), eq(15)))
                .thenReturn(draft);

        Map<String, Object> args = Map.of(
                "customerId", 202L,
                "items", List.of(Map.of("sku", "NG-CHARGER-01", "quantity", 1))
        );

        ToolExecutionResult result = stageOrderDraftTool.execute(args, staffContext);

        assertThat(result.success()).isTrue();
        assertThat(result.draftPayload()).isNotNull();
        assertThat(result.draftPayload().customerId()).isEqualTo(202L);
    }

    @Test
    void staff_stageDraftForNonExistentCustomer_emits404ProblemCard() {
        SessionContext staffContext = new SessionContext(
                "sess-staff", "agent-007", null, List.of(), "stage for 999", "agent-007", true
        );

        when(customerRepository.existsById(999L)).thenReturn(false);

        Map<String, Object> args = Map.of(
                "customerId", 999L,
                "items", List.of(Map.of("sku", "NG-CHARGER-01", "quantity", 1))
        );

        ToolExecutionResult result = stageOrderDraftTool.execute(args, staffContext);

        assertThat(result.success()).isFalse();
        assertThat(result.widgetType()).isEqualTo("PROBLEM_CARD");

        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) result.widgetPayload();
        assertThat(card.get("status")).isEqualTo(404);
        assertThat(card.get("title")).isEqualTo("Customer Not Found");
        assertThat(card.get("invalid_param")).isEqualTo("customerId");
        assertThat(card.get("received")).isEqualTo(999L);
        assertThat(card.get("remedy").toString()).contains("Verify the customer ID");
    }

    // =========================================================================
    // 3. AgencyOrchestrator Role Resolution
    // =========================================================================

    @Test
    void agencyOrchestrator_populatesIsStaffAndOperatorId_whenStaffAuthenticated() {
        AssistantModelClient modelClient = mock(AssistantModelClient.class);
        AssistantToolRegistry toolRegistry = mock(AssistantToolRegistry.class);
        AssistantSessionRepository sessionRepo = mock(AssistantSessionRepository.class);
        AssistantMessageRepository messageRepo = mock(AssistantMessageRepository.class);

        AgencyOrchestrator orchestrator = new AgencyOrchestrator(
                modelClient, toolRegistry, sessionRepo, messageRepo,
                mock(java.util.concurrent.ExecutorService.class), securityScoper
        );

        AssistantSession session = new AssistantSession();
        session.setId("sess-staff");
        session.setStatus(SessionStatus.ACTIVE);
        when(sessionRepo.findById("sess-staff")).thenReturn(Optional.of(session));

        SecurityContext sc = SecurityContextHolder.createEmptyContext();
        sc.setAuthentication(new UsernamePasswordAuthenticationToken(
                "staff-bob", "n/a", List.of(new SimpleGrantedAuthority("ROLE_STAFF"))
        ));
        SecurityContextHolder.setContext(sc);

        when(toolRegistry.getAllTools()).thenReturn(List.of());

        orchestrator.processUserMessage("sess-staff", "hello", mock(ModelStreamListener.class));

        verify(modelClient).streamChat(
                org.mockito.ArgumentMatchers.argThat(ctx ->
                        ctx.isStaff() && "staff-bob".equals(ctx.operatorId())
                ),
                any(),
                any()
        );
    }

    @Test
    void agencyOrchestrator_setsIsStaffFalse_whenShopperAuthenticated() {
        AssistantModelClient modelClient = mock(AssistantModelClient.class);
        AssistantToolRegistry toolRegistry = mock(AssistantToolRegistry.class);
        AssistantSessionRepository sessionRepo = mock(AssistantSessionRepository.class);
        AssistantMessageRepository messageRepo = mock(AssistantMessageRepository.class);

        AgencyOrchestrator orchestrator = new AgencyOrchestrator(
                modelClient, toolRegistry, sessionRepo, messageRepo,
                mock(java.util.concurrent.ExecutorService.class), securityScoper
        );

        AssistantSession session = new AssistantSession();
        session.setId("sess-shopper");
        session.setStatus(SessionStatus.ACTIVE);
        session.setUserId("alice");
        session.setCustomerId(101L);
        when(sessionRepo.findById("sess-shopper")).thenReturn(Optional.of(session));

        SecurityContext sc = SecurityContextHolder.createEmptyContext();
        sc.setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
        SecurityContextHolder.setContext(sc);

        when(toolRegistry.getAllTools()).thenReturn(List.of());

        orchestrator.processUserMessage("sess-shopper", "hello", mock(ModelStreamListener.class));

        verify(modelClient).streamChat(
                org.mockito.ArgumentMatchers.argThat(ctx ->
                        !ctx.isStaff() && ctx.operatorId() == null && Long.valueOf(101L).equals(ctx.customerId())
                ),
                any(),
                any()
        );
    }
}
