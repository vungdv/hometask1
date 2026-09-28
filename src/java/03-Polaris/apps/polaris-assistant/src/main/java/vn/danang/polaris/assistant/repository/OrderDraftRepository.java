package vn.danang.polaris.assistant.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.danang.polaris.assistant.entity.OrderDraft;

public interface OrderDraftRepository extends JpaRepository<OrderDraft, String> {

    /** Scoped to the session so a draft id from another session never resolves (IDOR). */
    Optional<OrderDraft> findByIdAndSessionId(String id, String sessionId);

    /**
     * The session's open ({@code WAITING_CONFIRMATION}) draft, if any. Looked up through the
     * unique {@code open_session_id} column, which is set only while a draft is open.
     */
    default Optional<OrderDraft> findOpenDraft(String sessionId) {
        return findByOpenSessionId(sessionId);
    }

    Optional<OrderDraft> findByOpenSessionId(String sessionId);
}
