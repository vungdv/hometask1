package vn.danang.polaris.assistant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import vn.danang.polaris.assistant.entity.AssistantMessage;

@Repository
public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    @Query("SELECT m FROM AssistantMessage m WHERE m.session.id = :sessionId ORDER BY m.createdAt ASC")
    List<AssistantMessage> findBySessionIdOrderByCreatedAtAsc(@Param("sessionId") String sessionId);
}
