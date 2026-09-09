package vn.danang.polaris.assistant.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.DraftStatus;

@Repository
public interface AssistantOrderDraftRepository extends JpaRepository<AssistantOrderDraft, String> {

    Optional<AssistantOrderDraft> findById(String id);

    @Query("SELECT d FROM AssistantOrderDraft d WHERE d.session.id = :sessionId AND d.status = :status")
    Optional<AssistantOrderDraft> findBySessionIdAndStatus(@Param("sessionId") String sessionId, @Param("status") DraftStatus status);

    @Query("SELECT d FROM AssistantOrderDraft d WHERE d.session.id = :sessionId ORDER BY d.createdAt DESC")
    List<AssistantOrderDraft> findBySessionIdOrderByCreatedAtDesc(@Param("sessionId") String sessionId);

    @Query("SELECT d FROM AssistantOrderDraft d WHERE d.status = :status AND d.expiresAt < :now")
    List<AssistantOrderDraft> findExpiredDraftsByStatusAndNow(@Param("status") DraftStatus status, @Param("now") Instant now);

    default List<AssistantOrderDraft> findExpiredDrafts(Instant now) {
        return findExpiredDraftsByStatusAndNow(DraftStatus.WAITING_CONFIRMATION, now);
    }
}
