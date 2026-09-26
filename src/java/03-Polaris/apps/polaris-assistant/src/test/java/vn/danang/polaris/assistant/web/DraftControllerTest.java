package vn.danang.polaris.assistant.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.assistant.dto.DraftItemSnapshot;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftStatus;
import vn.danang.polaris.assistant.service.DraftConfirmationService;
import vn.danang.polaris.assistant.service.DraftConfirmationService.AssistantOrderDraftResult;
import vn.danang.polaris.assistant.service.DraftConfirmationService.ConfirmOutcome;
import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.web.exception.DraftExpiredException;
import vn.danang.polaris.web.exception.DraftNotActionableException;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@WebMvcTest(DraftController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class DraftControllerTest {

    private static final String SESSION_ID = "sess-1";
    private static final String DRAFT_ID = "dft-abc";
    private static final String CONFIRM_URL = "/api/v1/assistant/sessions/" + SESSION_ID + "/drafts/" + DRAFT_ID + "/confirm";
    private static final String CANCEL_URL = "/api/v1/assistant/sessions/" + SESSION_ID + "/drafts/" + DRAFT_ID + "/cancel";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DraftConfirmationService confirmationService;

    private AssistantOrderDraft draft(AssistantOrderDraftStatus status) {
        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId(DRAFT_ID);
        draft.setSessionId(SESSION_ID);
        draft.setCustomerId(42L);
        draft.setStatus(status);
        draft.setTotalAmount(new BigDecimal("179.80"));
        draft.setExpiresAt(Instant.parse("2026-01-01T00:15:00Z"));
        return draft;
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a confirmable draft and sufficient stock, when confirmed, then 201 with Location header and Order's proxied body")
        void confirm_success_returns201WithLocationAndProxiedBody() throws Exception {
            String rawOrderJson = "{\"orderNumber\":\"ORD-1001\",\"status\":\"PLACED\"}";
            when(confirmationService.confirm(SESSION_ID, DRAFT_ID, "idem-key-1"))
                    .thenReturn(new ConfirmOutcome.Confirmed("ORD-1001", rawOrderJson));

            mockMvc.perform(post(CONFIRM_URL).header("Idempotency-Key", "idem-key-1"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/orders/ORD-1001"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().json(rawOrderJson));
        }

        @Test
        @DisplayName("Given a WAITING_CONFIRMATION draft, when cancelled, then 200 with status=CANCELLED")
        void cancel_success_returns200WithCancelledStatus() throws Exception {
            AssistantOrderDraft cancelled = draft(AssistantOrderDraftStatus.CANCELLED);
            when(confirmationService.cancel(SESSION_ID, DRAFT_ID))
                    .thenReturn(new AssistantOrderDraftResult(cancelled, List.of(
                            new DraftItemSnapshot("NG-WATCH-01", 2, new BigDecimal("89.90"), new BigDecimal("179.80")))));

            mockMvc.perform(post(CANCEL_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.draftId").value(DRAFT_ID))
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.items[0].sku").value("NG-WATCH-01"));
        }
    }

    // =========================================================================
    // 2. Invalid input
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("Given no Idempotency-Key header, when confirmed, then 400 Bad Request")
        void confirm_missingIdempotencyKey_returns400() throws Exception {
            mockMvc.perform(post(CONFIRM_URL))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Given no draft exists for this session, when confirmed, then 404")
        void confirm_draftNotFound_returns404() throws Exception {
            when(confirmationService.confirm(eq(SESSION_ID), eq(DRAFT_ID), eq("idem-key-1")))
                    .thenThrow(new ResourceNotFoundException("Order draft not found"));

            mockMvc.perform(post(CONFIRM_URL).header("Idempotency-Key", "idem-key-1"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Given an already-confirmed draft (double-click), when confirmed again, then 409 draft-not-actionable")
        void confirm_draftNotActionable_returns409() throws Exception {
            when(confirmationService.confirm(eq(SESSION_ID), eq(DRAFT_ID), eq("idem-key-1")))
                    .thenThrow(new DraftNotActionableException(DRAFT_ID, "CONFIRMED"));

            mockMvc.perform(post(CONFIRM_URL).header("Idempotency-Key", "idem-key-1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/draft-not-actionable"));
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given an expired draft, when confirmed, then 409 draft-expired")
        void confirm_expiredDraft_returns409DraftExpired() throws Exception {
            when(confirmationService.confirm(eq(SESSION_ID), eq(DRAFT_ID), eq("idem-key-1")))
                    .thenThrow(new DraftExpiredException(DRAFT_ID, "Draft expired"));

            mockMvc.perform(post(CONFIRM_URL).header("Idempotency-Key", "idem-key-1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/draft-expired"));
        }

        @Test
        @DisplayName("Given live stock now below the snapshotted quantity, when confirmed, then 400 out-of-stock problem with actions[]")
        void confirm_stockRejected_returns400WithActions() throws Exception {
            when(confirmationService.confirm(eq(SESSION_ID), eq(DRAFT_ID), eq("idem-key-1")))
                    .thenReturn(new ConfirmOutcome.StockRejected("NG-WATCH-01", 5, 1));

            mockMvc.perform(post(CONFIRM_URL).header("Idempotency-Key", "idem-key-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/out-of-stock"))
                    .andExpect(jsonPath("$.available_quantity").value(1))
                    .andExpect(jsonPath("$.actions", org.hamcrest.Matchers.hasSize(3)));
        }

        @Test
        @DisplayName("Given Order rejects the placement downstream, when confirmed, then the exact downstream status and body are proxied")
        void confirm_downstreamRejected_proxiesStatusAndBody() throws Exception {
            String problemJson = "{\"title\":\"Insufficient Stock\",\"status\":400}";
            when(confirmationService.confirm(eq(SESSION_ID), eq(DRAFT_ID), eq("idem-key-1")))
                    .thenReturn(new ConfirmOutcome.DownstreamRejected(400, problemJson));

            mockMvc.perform(post(CONFIRM_URL).header("Idempotency-Key", "idem-key-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(content().json(problemJson));
        }
    }
}
