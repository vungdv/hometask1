package vn.danang.polaris.assistant.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import vn.danang.polaris.assistant.entity.AssistantSession;

public interface AssistantSessionRepository extends JpaRepository<AssistantSession, String> {

    /** Loads the session under a row lock ({@code SELECT ... FOR UPDATE}); requires a transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AssistantSession s where s.id = :id")
    Optional<AssistantSession> findByIdForUpdate(@Param("id") String id);
}
