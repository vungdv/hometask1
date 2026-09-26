package vn.danang.polaris.assistant.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftRepository;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftStatus;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.AssistantSessionRepository;
import vn.danang.polaris.assistant.entity.AssistantSessionStatus;
import vn.danang.polaris.assistant.tools.CatalogRestClient;
import vn.danang.polaris.assistant.tools.CatalogRestClient.CatalogProductView;
import vn.danang.polaris.assistant.tools.OrderRestClient;
import vn.danang.polaris.assistant.tools.OrderRestClient.OrderPlacedView;

/**
 * Proves the interaction between {@link DraftConfirmationService#confirm} (the lazy, at-confirm
 * TTL check) and {@link DraftExpirationScheduler} (the eager, scheduled sweep) is safe when they
 * race for the same row, against a real Postgres so the {@code @Version} optimistic-lock check is
 * exercised for real rather than assumed.
 *
 * <p>Uses two real threads, synchronized with latches rather than sleeps, so the interleaving is
 * deterministic instead of timing-dependent: the sweep thread only proceeds once confirm()'s
 * thread has passed its own live-stock re-check (i.e. is about to call Order), and confirm()'s
 * thread only resumes once the sweep has actually committed in its own, independent transaction.
 * A single-threaded simulation (calling the sweep inline from within confirm()'s own call) was
 * tried first and rejected: it nests the sweep inside confirm()'s still-open transaction, so
 * confirm()'s eventual rollback silently rolls the sweep's change back too — masking exactly the
 * cross-transaction interaction this test exists to prove.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DraftConfirmationServiceConcurrencyIntegrationTest {

    private static final String SKU = "NG-WATCH-01";

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private AssistantSessionRepository sessionRepository;

    @Autowired
    private AssistantOrderDraftRepository draftRepository;

    @MockitoBean
    private CatalogRestClient catalogRestClient;

    @MockitoBean
    private OrderRestClient orderRestClient;

    @Test
    @DisplayName("Given the TTL sweep commits in its own transaction while a confirm is in flight, when confirm proceeds to persist CONFIRMED, "
            + "then the optimistic-lock version conflict surfaces instead of silently confirming an expired draft, "
            + "and the sweep's EXPIRED status survives even though confirm()'s own transaction rolls back")
    void confirm_racingSweepOnAnotherThread_failsClosedWithOptimisticLockConflict() throws Exception {
        String sessionId = "sess-race-" + UUID.randomUUID();
        String draftId = "dft-race-" + UUID.randomUUID();

        AssistantSession session = new AssistantSession();
        session.setId(sessionId);
        session.setUserId("user-1");
        session.setStatus(AssistantSessionStatus.WAITING_CONFIRMATION);
        sessionRepository.saveAndFlush(session);

        Instant expiresAt = Instant.now().plusSeconds(300);
        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId(draftId);
        draft.setSessionId(sessionId);
        draft.setCustomerId(42L);
        draft.setStatus(AssistantOrderDraftStatus.WAITING_CONFIRMATION);
        draft.setItemsJson("[{\"sku\":\"" + SKU + "\",\"quantity\":1,\"unitPrice\":10.00,\"lineTotal\":10.00}]");
        draft.setTotalAmount(new BigDecimal("10.00"));
        draft.setExpiresAt(expiresAt);
        draftRepository.saveAndFlush(draft);

        when(catalogRestClient.getBySku(SKU)).thenReturn(Optional.of(
                new CatalogProductView(SKU, "Nova Smart Watch", new BigDecimal("10.00"), 5, true)));

        CountDownLatch confirmReachedOrderCall = new CountDownLatch(1);
        CountDownLatch sweepCommitted = new CountDownLatch(1);
        ExecutorService sweepExecutor = Executors.newSingleThreadExecutor();

        // confirm()'s thread signals it has passed its own not-expired/stock checks and is about
        // to call Order, then blocks until the sweep (on the OTHER thread) has actually committed.
        when(orderRestClient.placeOrder(eq(42L), anyList(), anyString())).thenAnswer(invocation -> {
            confirmReachedOrderCall.countDown();
            if (!sweepCommitted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Sweep thread did not signal commit in time");
            }
            return new OrderPlacedView("ORD-RACE-1", "PLACED", new BigDecimal("10.00"), "{}");
        });

        try {
            Future<Integer> sweepResult = sweepExecutor.submit(() -> {
                if (!confirmReachedOrderCall.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Confirm thread did not reach the Order call in time");
                }
                try {
                    // Runs on its own thread -> its own independent transaction, which commits
                    // here, before confirm()'s transaction later attempts (and fails) its own save.
                    return draftRepository.updateStatusExpiredWhereWaitingAndPastTtl(expiresAt.plusSeconds(1));
                } finally {
                    sweepCommitted.countDown();
                }
            });

            assertThatThrownBy(() -> confirmationService.confirm(sessionId, draftId, "idem-race-1"))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);

            assertThat(sweepResult.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            sweepExecutor.shutdownNow();
        }

        AssistantOrderDraft finalState = draftRepository.findById(draftId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(AssistantOrderDraftStatus.EXPIRED);
        assertThat(finalState.getConfirmedOrderNumber()).isNull();
    }
}
