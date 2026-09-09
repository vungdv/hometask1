package vn.danang.polaris.assistant.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.assistant.dto.AssistantDraftResponse;
import vn.danang.polaris.assistant.dto.DraftItemDto;
import vn.danang.polaris.assistant.engine.AgencyOrchestrator;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.model.ModelEvent;
import vn.danang.polaris.assistant.model.ModelStreamListener;
import vn.danang.polaris.assistant.service.AssistantDraftService;
import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.entity.Customer;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.entity.OrderStatus;
import vn.danang.polaris.web.exception.DraftExpiredException;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;
import vn.danang.polaris.web.exception.ResourceNotFoundException;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest(AssistantStreamingController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class AssistantStreamingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgencyOrchestrator agencyOrchestrator;

    @MockitoBean
    private AssistantDraftService draftService;

    // ==========================================
    // 1. Security & Authentication Tests
    // ==========================================

    @Test
    void streamMessage_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void confirmDraft_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/drafts/draft-1/confirm"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancelDraft_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/drafts/draft-1/cancel"))
                .andExpect(status().isUnauthorized());
    }

    // ==========================================
    // 2. SSE Streaming Tests
    // ==========================================

    @Test
    void streamMessage_blankContent_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void streamMessage_invalidSessionId_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/sessions/invalid@session!/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void streamMessage_validRequest_streamsSseEventsAndCompletes() throws Exception {
        DraftItemDto item = new DraftItemDto("NG-EARBUD-01", "Earbuds", 1, new BigDecimal("49.99"), new BigDecimal("49.99"));
        AssistantDraftResponse draft = new AssistantDraftResponse(
                "draft-1", "sess-1", 100L, "WAITING_CONFIRMATION", List.of(item), new BigDecimal("49.99"), null, null, null, null
        );

        doAnswer(invocation -> {
            ModelStreamListener listener = invocation.getArgument(2);
            listener.onThought("Searching catalog...");
            listener.onToken("Found matching product.");
            listener.onWidget("PRODUCT_CARD", Map.of("sku", "NG-EARBUD-01"));
            listener.onDraft(draft);
            listener.onDone("STOP");
            return CompletableFuture.completedFuture(null);
        }).when(agencyOrchestrator).processUserMessageAsync(eq("sess-1"), eq("order earbuds"), any());

        MvcResult result = mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                        .content("{\"content\":\"order earbuds\"}")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE));

        String content = result.getResponse().getContentAsString();
        assertThat(content).contains("event:thought");
        assertThat(content).contains("Searching catalog...");
        assertThat(content).contains("event:token");
        assertThat(content).contains("Found matching product.");
        assertThat(content).contains("event:widget");
        assertThat(content).contains("PRODUCT_CARD");
        assertThat(content).contains("event:draft");
        assertThat(content).contains("draft-1");
        assertThat(content).contains("event:done");
        assertThat(content).contains("STOP");
    }

    // ==========================================
    // 3. Draft Confirmation Tests
    // ==========================================

    @Test
    void confirmDraft_validWaitingDraft_shouldReturn201WithOrderLocation() throws Exception {
        Customer customer = new Customer();
        customer.setId(100L);
        customer.setFullName("Alice");

        Order order = new Order();
        order.setId(10L);
        order.setOrderNumber("ORD-1001");
        order.setStatus(OrderStatus.PLACED);
        order.setTotalAmount(new BigDecimal("99.98"));
        order.setPlacedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        order.setCustomer(customer);
        order.setItems(new ArrayList<>());

        when(draftService.confirmDraft("sess-1", "draft-1", "idem-uuid-999")).thenReturn(order);

        mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/drafts/draft-1/confirm")
                        .header("Idempotency-Key", "idem-uuid-999")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/ORD-1001"))
                .andExpect(jsonPath("$.orderNumber").value("ORD-1001"))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalAmount").value(99.98));

        verify(draftService).confirmDraft("sess-1", "draft-1", "idem-uuid-999");
    }

    @Test
    void confirmDraft_expiredDraft_shouldReturn409ConflictDraftExpired() throws Exception {
        when(draftService.confirmDraft("sess-1", "draft-expired", null))
                .thenThrow(new DraftExpiredException("draft-expired", "Draft has expired (15-minute TTL elapsed). Please stage a new order draft."));

        mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/drafts/draft-expired/confirm")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Draft Expired"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.draftId").value("draft-expired"))
                .andExpect(jsonPath("$.remedy").exists());
    }

    @Test
    void confirmDraft_notFound_shouldReturn404() throws Exception {
        when(draftService.confirmDraft("sess-1", "draft-none", null))
                .thenThrow(new ResourceNotFoundException("Order draft not found with id: draft-none"));

        mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/drafts/draft-none/confirm")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    // ==========================================
    // 4. Draft Cancellation Tests
    // ==========================================

    @Test
    void cancelDraft_validWaitingDraft_shouldReturn200AndCancelledDraft() throws Exception {
        AssistantSession session = new AssistantSession();
        session.setId("sess-1");

        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId("draft-1");
        draft.setSession(session);
        draft.setCustomerId(100L);
        draft.setStatus(DraftStatus.CANCELLED);
        draft.setTotalAmount(new BigDecimal("50.00"));
        draft.setCreatedAt(Instant.now());
        draft.setUpdatedAt(Instant.now());

        when(draftService.cancelDraft("sess-1", "draft-1")).thenReturn(draft);

        mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/drafts/draft-1/cancel")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("draft-1"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(draftService).cancelDraft("sess-1", "draft-1");
    }

    @Test
    void cancelDraft_notFound_shouldReturn404() throws Exception {
        when(draftService.cancelDraft("sess-1", "draft-none"))
                .thenThrow(new ResourceNotFoundException("Order draft not found with id: draft-none"));

        mockMvc.perform(post("/api/v1/assistant/sessions/sess-1/drafts/draft-none/cancel")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }
}
