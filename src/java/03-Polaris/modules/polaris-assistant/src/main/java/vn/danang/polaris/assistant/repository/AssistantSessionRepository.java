package vn.danang.polaris.assistant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.SessionStatus;

@Repository
public interface AssistantSessionRepository extends JpaRepository<AssistantSession, String> {

    Optional<AssistantSession> findById(String id);

    List<AssistantSession> findByUserIdAndStatus(String userId, SessionStatus status);

    List<AssistantSession> findByUserIdOrderByCreatedAtDesc(String userId);
}
