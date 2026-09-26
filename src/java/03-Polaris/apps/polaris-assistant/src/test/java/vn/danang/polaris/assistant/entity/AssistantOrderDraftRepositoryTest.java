package vn.danang.polaris.assistant.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import vn.danang.polaris.assistant.TestcontainersConfiguration;

/**
 * Repository-level test proving the {@code assistant_order_drafts} schema (V12 migration) and
 * the {@link AssistantOrderDraft} JPA mapping agree, including the FK relationship to
 * {@code assistant_sessions} and the TTL-sweep bulk update. Runs against a real Postgres
 * (Testcontainers), the same profile used elsewhere in this module.
 */
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class AssistantOrderDraftRepositoryTest {

    private static final String ITEMS_JSON =
            "[{\"sku\":\"NG-EARBUD-01\",\"quantity\":2,\"unitPrice\":24.90,\"lineTotal\":49.80}]";

    @Autowired
    private AssistantSessionRepository sessionRepository;

    @Autowired
    private AssistantOrderDraftRepository draftRepository;

    @Autowired
    private EntityManager entityManager;

    private static String freshSessionId() {
        return "sess-" + UUID.randomUUID();
    }

    private static String freshDraftId() {
        return "dft-" + UUID.randomUUID();
    }

    private AssistantSession persistedSession() {
        AssistantSession session = new AssistantSession();
        session.setId(freshSessionId());
        session.setUserId("user-1");
        session.setCustomerId(1L);
        return sessionRepository.saveAndFlush(session);
    }

    private static AssistantOrderDraft newDraft(String id, String sessionId, AssistantOrderDraftStatus status,
            Instant expiresAt) {
        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId(id);
        draft.setSessionId(sessionId);
        draft.setCustomerId(1L);
        draft.setStatus(status);
        draft.setItemsJson(ITEMS_JSON);
        draft.setTotalAmount(new BigDecimal("49.80"));
        draft.setExpiresAt(expiresAt);
        return draft;
    }

    // --- Happy path -----------------------------------------------------

    @Test
    void save_shouldPersistDraftReferencingAnExistingSession() {
        AssistantSession session = persistedSession();
        AssistantOrderDraft draft = newDraft(freshDraftId(), session.getId(),
                AssistantOrderDraftStatus.WAITING_CONFIRMATION, Instant.now().plus(15, ChronoUnit.MINUTES));

        AssistantOrderDraft saved = draftRepository.saveAndFlush(draft);

        assertThat(saved.getVersion()).isZero();
        assertThat(draftRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void findBySessionIdAndStatus_shouldReturnOnlyMatchingStatus() {
        AssistantSession session = persistedSession();
        AssistantOrderDraft waiting = newDraft(freshDraftId(), session.getId(),
                AssistantOrderDraftStatus.WAITING_CONFIRMATION, Instant.now().plus(15, ChronoUnit.MINUTES));
        AssistantOrderDraft confirmed = newDraft(freshDraftId(), session.getId(),
                AssistantOrderDraftStatus.CONFIRMED, Instant.now().plus(15, ChronoUnit.MINUTES));
        draftRepository.saveAllAndFlush(List.of(waiting, confirmed));

        List<AssistantOrderDraft> result = draftRepository.findBySessionIdAndStatus(session.getId(),
                AssistantOrderDraftStatus.WAITING_CONFIRMATION);

        assertThat(result).extracting(AssistantOrderDraft::getId).containsExactly(waiting.getId());
    }

    @Test
    void expireWaitingDraftsPastTtl_shouldTransitionOnlyThatDraftAndIncrementItsVersion() {
        AssistantSession session = persistedSession();
        AssistantOrderDraft expiredCandidate = newDraft(freshDraftId(), session.getId(),
                AssistantOrderDraftStatus.WAITING_CONFIRMATION, Instant.now().minus(1, ChronoUnit.MINUTES));
        draftRepository.saveAndFlush(expiredCandidate);
        long versionBeforeSweep = expiredCandidate.getVersion();

        int expiredCount = draftRepository.updateStatusExpiredWhereWaitingAndPastTtl(Instant.now());

        assertThat(expiredCount).isEqualTo(1);
        AssistantOrderDraft reloaded = draftRepository.findById(expiredCandidate.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AssistantOrderDraftStatus.EXPIRED);
        assertThat(reloaded.getVersion()).isGreaterThan(versionBeforeSweep);
    }

    // --- Invalid input ----------------------------------------------------

    @Test
    void save_withNonExistentSessionId_shouldViolateForeignKeyConstraint() {
        AssistantOrderDraft draft = newDraft(freshDraftId(), "sess-does-not-exist",
                AssistantOrderDraftStatus.WAITING_CONFIRMATION, Instant.now().plus(15, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> draftRepository.saveAndFlush(draft))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Edge cases ---------------------------------------------------------

    @Test
    void findByIdAndSessionId_withMismatchedSession_shouldReturnEmpty_soOneSessionCannotGuessAnotherSDraftId() {
        AssistantSession owningSession = persistedSession();
        AssistantSession otherSession = persistedSession();
        AssistantOrderDraft draft = newDraft(freshDraftId(), owningSession.getId(),
                AssistantOrderDraftStatus.WAITING_CONFIRMATION, Instant.now().plus(15, ChronoUnit.MINUTES));
        draftRepository.saveAndFlush(draft);

        assertThat(draftRepository.findByIdAndSessionId(draft.getId(), otherSession.getId())).isEmpty();
        assertThat(draftRepository.findByIdAndSessionId(draft.getId(), owningSession.getId())).isPresent();
    }

    @Test
    void expireWaitingDraftsPastTtl_shouldLeaveNonWaitingDraftsUntouchedEvenIfPastTtl() {
        AssistantSession session = persistedSession();
        AssistantOrderDraft alreadyConfirmed = newDraft(freshDraftId(), session.getId(),
                AssistantOrderDraftStatus.CONFIRMED, Instant.now().minus(1, ChronoUnit.MINUTES));
        draftRepository.saveAndFlush(alreadyConfirmed);

        int expiredCount = draftRepository.updateStatusExpiredWhereWaitingAndPastTtl(Instant.now());

        assertThat(expiredCount).isZero();
        assertThat(draftRepository.findById(alreadyConfirmed.getId()).orElseThrow().getStatus())
                .isEqualTo(AssistantOrderDraftStatus.CONFIRMED);
    }

    @Test
    void deletingOwningSession_shouldCascadeDeleteItsDrafts() {
        AssistantSession session = persistedSession();
        AssistantOrderDraft draft = newDraft(freshDraftId(), session.getId(),
                AssistantOrderDraftStatus.WAITING_CONFIRMATION, Instant.now().plus(15, ChronoUnit.MINUTES));
        draftRepository.saveAndFlush(draft);

        sessionRepository.delete(session);
        sessionRepository.flush();
        // The cascade fires as a DB-level side effect of the FK constraint, not through
        // Hibernate's own persistence context, so it must be cleared before re-querying
        // or findById would just return the stale, still-managed draft instance.
        entityManager.clear();

        assertThat(draftRepository.findById(draft.getId())).isEmpty();
    }
}
