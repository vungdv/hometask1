package vn.danang.polaris.assistant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import vn.danang.polaris.assistant.dto.AssistantDraftResponse;
import vn.danang.polaris.assistant.dto.DraftItemDto;
import vn.danang.polaris.assistant.model.DeterministicRuleModelClient;
import vn.danang.polaris.assistant.model.ModelEvent;
import vn.danang.polaris.assistant.model.ModelStreamListener;
import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.assistant.tool.AssistantTool;
import vn.danang.polaris.assistant.tool.ToolDefinition;
import vn.danang.polaris.assistant.tool.ToolExecutionResult;
import vn.danang.polaris.dto.ProductResponse;

class DeterministicRuleModelClientTest {

    private DeterministicRuleModelClient client;
    private AssistantTool searchProductsTool;
    private AssistantTool getProductStockTool;
    private AssistantTool stageOrderDraftTool;
    private AssistantTool getOrderStatusTool;
    private AssistantTool cancelOrderReviewTool;
    private List<AssistantTool> tools;

    @BeforeEach
    void setUp() {
        client = new DeterministicRuleModelClient();

        searchProductsTool = mock(AssistantTool.class);
        when(searchProductsTool.getName()).thenReturn("search_products");
        when(searchProductsTool.getDefinition()).thenReturn(new ToolDefinition("search_products", "desc", Map.of()));

        getProductStockTool = mock(AssistantTool.class);
        when(getProductStockTool.getName()).thenReturn("get_product_stock");
        when(getProductStockTool.getDefinition()).thenReturn(new ToolDefinition("get_product_stock", "desc", Map.of()));

        stageOrderDraftTool = mock(AssistantTool.class);
        when(stageOrderDraftTool.getName()).thenReturn("stage_order_draft");
        when(stageOrderDraftTool.getDefinition()).thenReturn(new ToolDefinition("stage_order_draft", "desc", Map.of()));

        getOrderStatusTool = mock(AssistantTool.class);
        when(getOrderStatusTool.getName()).thenReturn("get_order_status");
        when(getOrderStatusTool.getDefinition()).thenReturn(new ToolDefinition("get_order_status", "desc", Map.of()));

        cancelOrderReviewTool = mock(AssistantTool.class);
        when(cancelOrderReviewTool.getName()).thenReturn("cancel_order_review");
        when(cancelOrderReviewTool.getDefinition()).thenReturn(new ToolDefinition("cancel_order_review", "desc", Map.of()));

        tools = List.of(
                searchProductsTool,
                getProductStockTool,
                stageOrderDraftTool,
                getOrderStatusTool,
                cancelOrderReviewTool
        );
    }

    @Test
    void streamChat_catalogSearchSingleProduct_emitsProductDetailsAndStagingSuggestion() {
        ProductResponse earbud = new ProductResponse(
                1L, "NG-EARBUD-01", "NextGen Wireless Earbuds", "Premium earbuds",
                "Audio", new BigDecimal("49.99"), 25, true, true, null
        );

        when(searchProductsTool.execute(any(), any()))
                .thenReturn(ToolExecutionResult.success("search_products", List.of(earbud)));

        List<ModelEvent> capturedEvents = new ArrayList<>();
        ModelStreamListener listener = capturedEvents::add;

        SessionContext context = new SessionContext("sess-1", "user-1", 100L, List.of(), "Find earbud under $60");
        client.streamChat(context, tools, listener);

        // Verify search tool called with maxPrice 60
        verify(searchProductsTool).execute(argThat(args ->
                args.containsKey("maxPrice") && new BigDecimal("60").compareTo(new BigDecimal(args.get("maxPrice").toString())) == 0
        ), any());

        // Verify event stream
        assertThat(capturedEvents).isNotEmpty();
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.ThoughtEvent)).isTrue();
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.TokenDeltaEvent t && t.delta().contains("NextGen Wireless Earbuds"))).isTrue();
        assertThat(capturedEvents.get(capturedEvents.size() - 1)).isEqualTo(new ModelEvent.DoneEvent("STOP"));
    }

    @Test
    void streamChat_catalogSearchMultipleProducts_emitsDisambiguationList() {
        ProductResponse p1 = new ProductResponse(1L, "NG-CHARGER-01", "Charger Fast", "Fast", "Accessories", new BigDecimal("25.00"), 50, true, true, null);
        ProductResponse p2 = new ProductResponse(2L, "NG-CHARGER-02", "Charger Wireless", "Pad", "Accessories", new BigDecimal("35.00"), 20, true, true, null);

        when(searchProductsTool.execute(any(), any()))
                .thenReturn(ToolExecutionResult.success("search_products", List.of(p1, p2)));

        List<ModelEvent> capturedEvents = new ArrayList<>();
        ModelStreamListener listener = capturedEvents::add;

        SessionContext context = new SessionContext("sess-1", "user-1", 100L, List.of(), "search charger");
        client.streamChat(context, tools, listener);

        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.WidgetEvent w && "PRODUCT_LIST_CARD".equals(w.widgetType()))).isTrue();
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.TokenDeltaEvent t && t.delta().contains("Found 2 matching products"))).isTrue();
        assertThat(capturedEvents.get(capturedEvents.size() - 1)).isEqualTo(new ModelEvent.DoneEvent("STOP"));
    }

    @Test
    void streamChat_stockCheckIntent_dispatchesGetProductStockTool() {
        ProductResponse watch = new ProductResponse(
                3L, "NG-WATCH-01", "NextGen Smart Watch", "Watch",
                "Wearables", new BigDecimal("199.99"), 14, true, true, null
        );

        when(getProductStockTool.execute(any(), any()))
                .thenReturn(ToolExecutionResult.successWithWidget("get_product_stock", watch, "PRODUCT_CARD", watch));

        List<ModelEvent> capturedEvents = new ArrayList<>();
        ModelStreamListener listener = capturedEvents::add;

        SessionContext context = new SessionContext("sess-1", "user-1", 100L, List.of(), "check stock NG-WATCH-01");
        client.streamChat(context, tools, listener);

        verify(getProductStockTool).execute(argThat(args -> "NG-WATCH-01".equals(args.get("sku"))), any());
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.WidgetEvent w && "PRODUCT_CARD".equals(w.widgetType()))).isTrue();
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.TokenDeltaEvent t && t.delta().contains("14 units in stock"))).isTrue();
        assertThat(capturedEvents.get(capturedEvents.size() - 1)).isEqualTo(new ModelEvent.DoneEvent("STOP"));
    }

    @Test
    void streamChat_orderStagingIntent_stagesOrderDraftAndHaltsSafely() {
        DraftItemDto item = new DraftItemDto("NG-EARBUD-01", "NextGen Wireless Earbuds", 2, new BigDecimal("49.99"), new BigDecimal("99.98"));
        AssistantDraftResponse draft = new AssistantDraftResponse(
                "draft-1", "sess-1", 100L, "WAITING_CONFIRMATION", List.of(item), new BigDecimal("99.98"), null, null, null, null
        );

        when(stageOrderDraftTool.execute(any(), any()))
                .thenReturn(ToolExecutionResult.successWithDraft("stage_order_draft", draft, draft));

        List<ModelEvent> capturedEvents = new ArrayList<>();
        ModelStreamListener listener = capturedEvents::add;

        SessionContext context = new SessionContext("sess-1", "user-1", 100L, List.of(), "Order 2 units of NG-EARBUD-01");
        client.streamChat(context, tools, listener);

        verify(stageOrderDraftTool).execute(argThat(args -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) args.get("items");
            return items != null && items.size() == 1 && "NG-EARBUD-01".equals(items.get(0).get("sku")) && Integer.valueOf(2).equals(items.get(0).get("quantity"));
        }), any());

        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.DraftEvent d && "draft-1".equals(d.draft().id()))).isTrue();
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.TokenDeltaEvent t && t.delta().contains("Order draft staged totaling $99.98"))).isTrue();
        assertThat(capturedEvents.get(capturedEvents.size() - 1)).isEqualTo(new ModelEvent.DoneEvent("STOP"));
    }

    @Test
    void streamChat_orderTrackingIntent_dispatchesGetOrderStatusTool() {
        vn.danang.polaris.dto.OrderResponse order = new vn.danang.polaris.dto.OrderResponse(
                1L, "ORD-1001", vn.danang.polaris.entity.OrderStatus.CONFIRMED,
                new BigDecimal("99.98"), java.time.Instant.now(), java.time.Instant.now(), "Alice", List.of()
        );

        when(getOrderStatusTool.execute(any(), any()))
                .thenReturn(ToolExecutionResult.successWithWidget("get_order_status", order, "ORDER_STATUS_CARD", order));

        List<ModelEvent> capturedEvents = new ArrayList<>();
        ModelStreamListener listener = capturedEvents::add;

        SessionContext context = new SessionContext("sess-1", "user-1", 100L, List.of(), "Track status of ORD-1001");
        client.streamChat(context, tools, listener);

        verify(getOrderStatusTool).execute(argThat(args -> "ORD-1001".equals(args.get("orderNumber"))), any());
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.WidgetEvent w && "ORDER_STATUS_CARD".equals(w.widgetType()))).isTrue();
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.TokenDeltaEvent t && t.delta().contains("CONFIRMED"))).isTrue();
        assertThat(capturedEvents.get(capturedEvents.size() - 1)).isEqualTo(new ModelEvent.DoneEvent("STOP"));
    }

    @Test
    void streamChat_cancelOrderReviewIntent_dispatchesCancelOrderReviewTool() {
        Map<String, Object> reviewData = Map.of(
                "orderNumber", "ORD-1001",
                "currentStatus", "PLACED",
                "cancellable", true,
                "restockNotice", "Cancelling ORD-1001 will return 2x NG-EARBUD-01 to inventory."
        );

        when(cancelOrderReviewTool.execute(any(), any()))
                .thenReturn(ToolExecutionResult.successWithWidget("cancel_order_review", reviewData, "CANCELLATION_REVIEW_CARD", reviewData));

        List<ModelEvent> capturedEvents = new ArrayList<>();
        ModelStreamListener listener = capturedEvents::add;

        SessionContext context = new SessionContext("sess-1", "user-1", 100L, List.of(), "Cancel ORD-1001");
        client.streamChat(context, tools, listener);

        verify(cancelOrderReviewTool).execute(argThat(args -> "ORD-1001".equals(args.get("orderNumber"))), any());
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.WidgetEvent w && "CANCELLATION_REVIEW_CARD".equals(w.widgetType()))).isTrue();
        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.TokenDeltaEvent t && t.delta().contains("return 2x NG-EARBUD-01"))).isTrue();
        assertThat(capturedEvents.get(capturedEvents.size() - 1)).isEqualTo(new ModelEvent.DoneEvent("STOP"));
    }

    @Test
    void streamChat_fallbackIntent_providesAssistancePrompt() {
        List<ModelEvent> capturedEvents = new ArrayList<>();
        ModelStreamListener listener = capturedEvents::add;

        SessionContext context = new SessionContext("sess-1", "user-1", 100L, List.of(), "Hello, can you help me?");
        client.streamChat(context, tools, listener);

        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.TokenDeltaEvent t && t.delta().contains("search our catalog"))).isTrue();
        assertThat(capturedEvents.get(capturedEvents.size() - 1)).isEqualTo(new ModelEvent.DoneEvent("STOP"));
    }
}
