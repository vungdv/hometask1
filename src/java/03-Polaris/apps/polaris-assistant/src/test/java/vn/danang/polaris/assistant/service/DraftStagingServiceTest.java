package vn.danang.polaris.assistant.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.dto.OrderItemRequest;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftRepository;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftStatus;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.AssistantSessionRepository;
import vn.danang.polaris.assistant.entity.AssistantSessionStatus;
import vn.danang.polaris.assistant.service.DraftStagingService.StageOutcome;
import vn.danang.polaris.assistant.tools.CatalogRestClient;
import vn.danang.polaris.assistant.tools.CatalogRestClient.CatalogProductView;

/**
 * Unit tests for {@link DraftStagingService} — the read-only stock verification and
 * insert-or-update-by-draftId staging orchestration WO-020 specifies. Exercises the WO's
 * Given/When/Then acceptance criteria directly at this seam (mocking Catalog and the WO-019
 * repositories), independent of the SSE transport or the MCP tool-call plumbing above it.
 */
class DraftStagingServiceTest {

    private static final String SESSION_ID = "sess-test-1";
    private static final String USER_ID = "user-1";
    private static final Long CUSTOMER_ID = 42L;
    private static final String SKU = "NG-WATCH-01";

    private AssistantSessionRepository sessionRepository;
    private AssistantOrderDraftRepository draftRepository;
    private CatalogRestClient catalogRestClient;
    private DraftStagingService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AssistantSessionRepository.class);
        draftRepository = mock(AssistantOrderDraftRepository.class);
        catalogRestClient = mock(CatalogRestClient.class);
        AssistantDraftProperties draftProperties = new AssistantDraftProperties();

        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new DraftStagingService(sessionRepository, draftRepository, catalogRestClient,
                new ObjectMapper(), draftProperties);
    }

    private CatalogProductView product(int stockQuantity, String priceStr) {
        return new CatalogProductView(SKU, "Nova Smart Watch", new BigDecimal(priceStr), stockQuantity, true);
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given no prior session, when staging a new draft with sufficient stock, then a session and a draft are created")
        void stage_noExistingSession_createsSessionAndDraft() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(product(10, "89.90")));

            StageOutcome outcome = service.stage(SESSION_ID, USER_ID, CUSTOMER_ID, null,
                    List.of(new OrderItemRequest(SKU, 2)));

            assertThat(outcome).isInstanceOf(StageOutcome.Staged.class);
            StageOutcome.Staged staged = (StageOutcome.Staged) outcome;
            assertThat(staged.draft().getId()).startsWith("dft-");
            assertThat(staged.draft().getStatus()).isEqualTo(AssistantOrderDraftStatus.WAITING_CONFIRMATION);
            assertThat(staged.draft().getTotalAmount()).isEqualByComparingTo("179.80");
            assertThat(staged.items()).hasSize(1);

            verify(sessionRepository, org.mockito.Mockito.atLeastOnce()).save(any());
            verify(draftRepository).save(any());
        }

        @Test
        @DisplayName("Given an existing draft_id for the same session and a smaller in-stock quantity, when staged, then the same row is updated in place")
        void stage_existingDraftIdSameSession_updatesInPlace() {
            AssistantSession session = activeSession();
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

            AssistantOrderDraft existing = new AssistantOrderDraft();
            existing.setId("dft-abc");
            existing.setSessionId(SESSION_ID);
            existing.setCustomerId(CUSTOMER_ID);
            existing.setStatus(AssistantOrderDraftStatus.WAITING_CONFIRMATION);
            existing.setTotalAmount(new BigDecimal("179.80"));
            existing.setExpiresAt(Instant.now().plusSeconds(60));
            when(draftRepository.findByIdAndSessionId("dft-abc", SESSION_ID)).thenReturn(Optional.of(existing));
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(product(10, "89.90")));

            StageOutcome outcome = service.stage(SESSION_ID, USER_ID, CUSTOMER_ID, "dft-abc",
                    List.of(new OrderItemRequest(SKU, 1)));

            assertThat(outcome).isInstanceOf(StageOutcome.Staged.class);
            StageOutcome.Staged staged = (StageOutcome.Staged) outcome;
            assertThat(staged.draft().getId()).isEqualTo("dft-abc");
            assertThat(staged.draft().getTotalAmount()).isEqualByComparingTo("89.90");
        }

        @Test
        @DisplayName("Given a session in a terminal state (CONFIRMED), when a new turn stages a draft, then the session resumes to WAITING_CONFIRMATION (via ACTIVE)")
        void stage_terminalSession_resumesAndTransitionsToWaitingConfirmation() {
            AssistantSession session = activeSession();
            session.setStatus(AssistantSessionStatus.CONFIRMED);
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(product(10, "89.90")));

            service.stage(SESSION_ID, USER_ID, CUSTOMER_ID, null, List.of(new OrderItemRequest(SKU, 1)));

            assertThat(session.getStatus()).isEqualTo(AssistantSessionStatus.WAITING_CONFIRMATION);
        }
    }

    // =========================================================================
    // 2. Invalid input — insufficient stock rejections
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("Given requested quantity exceeding live stock, when staged, then Rejected is returned and no draft row is persisted")
        void stage_insufficientStock_returnsRejectedAndDoesNotPersistDraft() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(product(3, "89.90")));

            StageOutcome outcome = service.stage(SESSION_ID, USER_ID, CUSTOMER_ID, null,
                    List.of(new OrderItemRequest(SKU, 10)));

            assertThat(outcome).isInstanceOf(StageOutcome.Rejected.class);
            StageOutcome.Rejected rejected = (StageOutcome.Rejected) outcome;
            assertThat(rejected.sku()).isEqualTo(SKU);
            assertThat(rejected.requested()).isEqualTo(10);
            assertThat(rejected.available()).isEqualTo(3);
            assertThat(rejected.actions()).extracting(a -> a.get("action"))
                    .containsExactly("adjust_quantity", "search_alternatives", "remove_item");
            verify(draftRepository, never()).save(any());
        }

        @Test
        @DisplayName("Given a SKU Catalog doesn't recognize, when staged, then it is treated as zero available and Rejected without adjust_quantity")
        void stage_unknownSku_treatedAsZeroAvailable() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));
            when(catalogRestClient.getBySku("MISSING-SKU")).thenReturn(Optional.empty());

            StageOutcome outcome = service.stage(SESSION_ID, USER_ID, CUSTOMER_ID, null,
                    List.of(new OrderItemRequest("MISSING-SKU", 1)));

            assertThat(outcome).isInstanceOf(StageOutcome.Rejected.class);
            StageOutcome.Rejected rejected = (StageOutcome.Rejected) outcome;
            assertThat(rejected.available()).isZero();
            assertThat(rejected.actions()).extracting(a -> a.get("action")).doesNotContain("adjust_quantity");
        }

        @Test
        @DisplayName("Given an existing draft and an update attempt that now exceeds stock, when staged, then the existing draft row is left completely untouched")
        void stage_rejectedUpdateAttempt_leavesExistingDraftUnchanged() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(product(2, "89.90")));

            StageOutcome outcome = service.stage(SESSION_ID, USER_ID, CUSTOMER_ID, "dft-abc",
                    List.of(new OrderItemRequest(SKU, 10)));

            assertThat(outcome).isInstanceOf(StageOutcome.Rejected.class);
            // Rejected before ever looking up (or touching) the existing row by draftId.
            verify(draftRepository, never()).findByIdAndSessionId(anyString(), anyString());
            verify(draftRepository, never()).save(any());
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given a draft_id that doesn't belong to this session, when staged with sufficient stock, then a brand-new draft is inserted instead of updating a foreign row")
        void stage_draftIdNotFoundForSession_insertsNewDraft() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));
            when(draftRepository.findByIdAndSessionId("dft-not-mine", SESSION_ID)).thenReturn(Optional.empty());
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(product(10, "89.90")));

            StageOutcome outcome = service.stage(SESSION_ID, USER_ID, CUSTOMER_ID, "dft-not-mine",
                    List.of(new OrderItemRequest(SKU, 1)));

            assertThat(outcome).isInstanceOf(StageOutcome.Staged.class);
            StageOutcome.Staged staged = (StageOutcome.Staged) outcome;
            assertThat(staged.draft().getId()).isNotEqualTo("dft-not-mine").startsWith("dft-");
        }

        @Test
        @DisplayName("Given Catalog is unreachable, when staged, then the exception propagates (fail closed) and no draft row is ever persisted")
        void stage_catalogUnreachable_propagatesAndDoesNotPersistDraft() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));
            when(catalogRestClient.getBySku(SKU)).thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));

            assertThatThrownBy(() -> service.stage(SESSION_ID, USER_ID, CUSTOMER_ID, null, List.of(new OrderItemRequest(SKU, 1))))
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);

            verify(draftRepository, never()).save(any());
        }

        @Test
        @DisplayName("Given two requested items where the second is out of stock, when staged, then the first (in-stock) item's SKU is not the one reported as rejected")
        void stage_secondItemInsufficientStock_reportsFailingSku() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));
            when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(product(10, "89.90")));
            when(catalogRestClient.getBySku("NG-EARBUD-01")).thenReturn(Optional.of(product(1, "24.90")));

            StageOutcome outcome = service.stage(SESSION_ID, USER_ID, CUSTOMER_ID, null,
                    List.of(new OrderItemRequest(SKU, 1), new OrderItemRequest("NG-EARBUD-01", 5)));

            assertThat(outcome).isInstanceOf(StageOutcome.Rejected.class);
            assertThat(((StageOutcome.Rejected) outcome).sku()).isEqualTo("NG-EARBUD-01");
        }
    }

    private static AssistantSession activeSession() {
        AssistantSession session = new AssistantSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setCustomerId(CUSTOMER_ID);
        session.setStatus(AssistantSessionStatus.ACTIVE);
        return session;
    }
}
