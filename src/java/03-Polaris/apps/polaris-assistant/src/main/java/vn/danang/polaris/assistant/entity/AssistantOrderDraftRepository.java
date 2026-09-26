package vn.danang.polaris.assistant.entity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AssistantOrderDraftRepository extends JpaRepository<AssistantOrderDraft, String> {

    /**
     * Scoped defensively to the owning session so one session can never touch another's
     * draft by guessing an id (WO-020 update-by-draftId, WO-021 confirm/cancel).
     */
    Optional<AssistantOrderDraft> findByIdAndSessionId(String id, String sessionId);

    List<AssistantOrderDraft> findBySessionIdAndStatus(String sessionId, AssistantOrderDraftStatus status);

    /**
     * Bulk-expires drafts whose TTL has lapsed while still awaiting confirmation. The primitive
     * WO-021's scheduled TTL sweep calls; exploits {@code idx_assistant_drafts_expiry}.
     *
     * <p>Explicitly {@code @Transactional}: {@link DraftExpirationScheduler} calls this with no
     * ambient transaction of its own (it runs on Spring's scheduling thread, not a request
     * thread), and a default interface method isn't reliably wrapped in one just by delegating to
     * an {@code @Modifying} query - {@code flushAutomatically = true} needs a real transaction to
     * flush against, or it fails with "No EntityManager with actual transaction available".
     */
    @Transactional
    default int updateStatusExpiredWhereWaitingAndPastTtl(Instant now) {
        return expireWaitingDraftsPastTtl(AssistantOrderDraftStatus.WAITING_CONFIRMATION,
                AssistantOrderDraftStatus.EXPIRED, now);
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AssistantOrderDraft d SET d.status = :expired, d.version = d.version + 1 "
            + "WHERE d.status = :waiting AND d.expiresAt < :now")
    int expireWaitingDraftsPastTtl(@Param("waiting") AssistantOrderDraftStatus waiting,
            @Param("expired") AssistantOrderDraftStatus expired, @Param("now") Instant now);
}
