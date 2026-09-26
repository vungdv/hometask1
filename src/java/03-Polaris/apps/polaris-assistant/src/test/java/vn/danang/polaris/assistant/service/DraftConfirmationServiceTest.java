package vn.danang.polaris.assistant.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftRepository;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftStatus;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.AssistantSessionRepository;
import vn.danang.polaris.assistant.entity.AssistantSessionStatus;
import vn.danang.polaris.assistant.service.DraftConfirmationService.ConfirmOutcome;
import vn.danang.polaris.assistant.tools.CatalogRestClient;
import vn.danang.polaris.assistant.tools.CatalogRestClient.CatalogProductView;
import vn.danang.polaris.assistant.tools.OrderPlacementRejectedException;
import vn.danang.polaris.assistant.tools.OrderRestClient;
import vn.danang.polaris.assistant.tools.OrderRestClient.OrderPlacedView;
import vn.danang.polaris.web.exception.DraftExpiredException;
import vn.danang.polaris.web.exception.DraftNotActionableException;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

/**
 * Unit tests for {@link DraftConfirmationService} — WO-021's confirm/cancel business logic,
 * exercised directly at this seam (mocking Catalog, Order, and the WO-019 repositories),
 * independent of the REST/HTTP layer.
 */
class DraftConfirmationServiceTest {

    private static final String SESSION_ID = "sess-1";
    private static final String DRAFT_ID = "dft-abc";
    private static final String SKU = "NG-WATCH-01";
    private static final String ITEMS_JSON =
            "[{\"sku\":\"NG-WATCH-01\",\"quantity\":2,\"unitPrice\":89.90,\"lineTotal\":179.80}]";

    private AssistantOrderDraftRepository draftRepository;
    private AssistantSessionRepository sessionRepository;
    private CatalogRestClient catalogRestClient;
    private OrderRestClient orderRestClient;
    private DraftConfirmationService service;

    @BeforeEach
    void setUp() {
        draftRepository = mock(AssistantOrderDraftRepository.class);
        sessionRepository = mock(AssistantSessionRepository.class);
        catalogRestClient = mock(CatalogRestClient.class);
        orderRestClient = mock(OrderRestClient.class);
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new DraftConfirmationService(draftRepository, sessionRepository, catalogRestClient,
                orderRestClient, new ObjectMapper());
    }

    private AssistantOrderDraft waitingDraft(Instant expiresAt) {
        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId(DRAFT_ID);
        draft.setSessionId(SESSION_ID);
        draft.setCustomerId(42L);
        draft.setStatus(AssistantOrderDraftStatus.WAITING_CONFIRMATION);
        draft.setItemsJson(ITEMS_JSON);
        draft.setTotalAmount(new BigDecimal("179.80"));
        draft.setExpiresAt(expiresAt);
        return draft;
    }

    private AssistantSession activeSession() {
        AssistantSession session = new AssistantSession();
        session.setId(SESSION_ID);
        session.setUserId("user-1");
        session.setStatus(AssistantSessionStatus.WAITING_CONFIRMATION);
        return session;
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a WAITING_CONFIRMATION draft within TTL and sufficient live stock, when confirmed, then the order is placed and the draft/session transition to CONFIRMED")
        void confirm_withinTtlAndSufficientStock_placesOrderAndTransitionsToConfirmed() {
            AssistantOrderDraft draft = waitingDraft(Instant.now().plus(10, ChronoUnit.MINUTES));
            when(draftRepository.findByIdAndSessionId(DRAFT_ID, SESSION_ID)).thenReturn(Optional.of(draft));
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(
                    new CatalogProductView(SKU, "Nova Smart Watch", new BigDecimal("89.90"), 10, true)));
            when(orderRestClient.placeOrder(eq(42L), anyList(), eq("idem-key-1")))
                    .thenReturn(new OrderPlacedView("ORD-1001", "PLACED", new BigDecimal("179.80"), "{\"orderNumber\":\"ORD-1001\"}"));

            ConfirmOutcome outcome = service.confirm(SESSION_ID, DRAFT_ID, "idem-key-1");

            assertThat(outcome).isInstanceOf(ConfirmOutcome.Confirmed.class);
            ConfirmOutcome.Confirmed confirmed = (ConfirmOutcome.Confirmed) outcome;
            assertThat(confirmed.orderNumber()).isEqualTo("ORD-1001");
            assertThat(draft.getStatus()).isEqualTo(AssistantOrderDraftStatus.CONFIRMED);
            assertThat(draft.getConfirmedOrderNumber()).isEqualTo("ORD-1001");
        }

        @Test
        @DisplayName("Given a WAITING_CONFIRMATION draft, when cancelled, then the draft/session transition to CANCELLED")
        void cancel_waitingDraft_transitionsToCancelled() {
            AssistantOrderDraft draft = waitingDraft(Instant.now().plus(10, ChronoUnit.MINUTES));
            when(draftRepository.findByIdAndSessionId(DRAFT_ID, SESSION_ID)).thenReturn(Optional.of(draft));
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));

            var result = service.cancel(SESSION_ID, DRAFT_ID);

            assertThat(result.draft().getStatus()).isEqualTo(AssistantOrderDraftStatus.CANCELLED);
            assertThat(result.items()).hasSize(1);
        }
    }

    // =========================================================================
    // 2. Invalid input
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("Given no draft matches this session, when confirmed, then ResourceNotFoundException is thrown and Order is never called")
        void confirm_draftNotFound_throwsResourceNotFound() {
            when(draftRepository.findByIdAndSessionId(DRAFT_ID, SESSION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirm(SESSION_ID, DRAFT_ID, "idem-1"))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(orderRestClient, never()).placeOrder(any(), any(), any());
        }

        @Test
        @DisplayName("Given an already-CONFIRMED draft, when confirmed again (double-click), then DraftNotActionableException is thrown and no duplicate order is placed")
        void confirm_alreadyConfirmed_throwsDraftNotActionable() {
            AssistantOrderDraft draft = waitingDraft(Instant.now().plus(10, ChronoUnit.MINUTES));
            draft.setStatus(AssistantOrderDraftStatus.CONFIRMED);
            when(draftRepository.findByIdAndSessionId(DRAFT_ID, SESSION_ID)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.confirm(SESSION_ID, DRAFT_ID, "idem-1"))
                    .isInstanceOf(DraftNotActionableException.class);
            verify(orderRestClient, never()).placeOrder(any(), any(), any());
        }

        @Test
        @DisplayName("Given a draft whose expires_at is in the past, when confirmed, then DraftExpiredException is thrown, the draft is marked EXPIRED, and Order is never called")
        void confirm_expiredDraft_throwsDraftExpiredAndMarksExpired() {
            AssistantOrderDraft draft = waitingDraft(Instant.now().minus(1, ChronoUnit.MINUTES));
            when(draftRepository.findByIdAndSessionId(DRAFT_ID, SESSION_ID)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.confirm(SESSION_ID, DRAFT_ID, "idem-1"))
                    .isInstanceOf(DraftExpiredException.class);

            assertThat(draft.getStatus()).isEqualTo(AssistantOrderDraftStatus.EXPIRED);
            verify(orderRestClient, never()).placeOrder(any(), any(), any());
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given the snapshotted quantity now exceeds live stock, when confirmed, then StockRejected is returned, the draft stays WAITING_CONFIRMATION, and Order is never called")
        void confirm_liveStockDropped_returnsStockRejectedAndLeavesDraftUnchanged() {
            AssistantOrderDraft draft = waitingDraft(Instant.now().plus(10, ChronoUnit.MINUTES));
            when(draftRepository.findByIdAndSessionId(DRAFT_ID, SESSION_ID)).thenReturn(Optional.of(draft));
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(
                    new CatalogProductView(SKU, "Nova Smart Watch", new BigDecimal("89.90"), 1, true)));

            ConfirmOutcome outcome = service.confirm(SESSION_ID, DRAFT_ID, "idem-1");

            assertThat(outcome).isInstanceOf(ConfirmOutcome.StockRejected.class);
            ConfirmOutcome.StockRejected rejected = (ConfirmOutcome.StockRejected) outcome;
            assertThat(rejected.available()).isEqualTo(1);
            assertThat(draft.getStatus()).isEqualTo(AssistantOrderDraftStatus.WAITING_CONFIRMATION);
            verify(orderRestClient, never()).placeOrder(any(), any(), any());
        }

        @Test
        @DisplayName("Given Order rejects the placement downstream (race window), when confirmed, then DownstreamRejected proxies the status/body and the draft stays WAITING_CONFIRMATION")
        void confirm_downstreamRejection_proxiesAndLeavesDraftWaiting() {
            AssistantOrderDraft draft = waitingDraft(Instant.now().plus(10, ChronoUnit.MINUTES));
            when(draftRepository.findByIdAndSessionId(DRAFT_ID, SESSION_ID)).thenReturn(Optional.of(draft));
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(
                    new CatalogProductView(SKU, "Nova Smart Watch", new BigDecimal("89.90"), 10, true)));
            when(orderRestClient.placeOrder(any(), any(), any()))
                    .thenThrow(new OrderPlacementRejectedException(400, "{\"title\":\"Insufficient Stock\"}", null));

            ConfirmOutcome outcome = service.confirm(SESSION_ID, DRAFT_ID, "idem-1");

            assertThat(outcome).isInstanceOf(ConfirmOutcome.DownstreamRejected.class);
            ConfirmOutcome.DownstreamRejected downstream = (ConfirmOutcome.DownstreamRejected) outcome;
            assertThat(downstream.statusCode()).isEqualTo(400);
            assertThat(draft.getStatus()).isEqualTo(AssistantOrderDraftStatus.WAITING_CONFIRMATION);
        }

        @Test
        @DisplayName("Given an already-expired draft, when cancelled, then it succeeds as a tidy-up rather than an error")
        void cancel_expiredButStillWaitingDraft_succeeds() {
            // Not yet swept by the scheduler, but past its TTL - cancel is a no-op-ish tidy-up per Task 6,
            // not an error, so the TTL check that confirm() has is deliberately absent here.
            AssistantOrderDraft draft = waitingDraft(Instant.now().minus(1, ChronoUnit.MINUTES));
            when(draftRepository.findByIdAndSessionId(DRAFT_ID, SESSION_ID)).thenReturn(Optional.of(draft));

            var result = service.cancel(SESSION_ID, DRAFT_ID);

            assertThat(result.draft().getStatus()).isEqualTo(AssistantOrderDraftStatus.CANCELLED);
        }

        @Test
        @DisplayName("Given an already-cancelled draft, when cancelled again, then DraftNotActionableException is thrown")
        void cancel_alreadyCancelled_throwsDraftNotActionable() {
            AssistantOrderDraft draft = waitingDraft(Instant.now().plus(10, ChronoUnit.MINUTES));
            draft.setStatus(AssistantOrderDraftStatus.CANCELLED);
            when(draftRepository.findByIdAndSessionId(DRAFT_ID, SESSION_ID)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.cancel(SESSION_ID, DRAFT_ID))
                    .isInstanceOf(DraftNotActionableException.class);
        }
    }
}
