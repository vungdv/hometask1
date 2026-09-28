package vn.danang.polaris.assistant.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.danang.polaris.assistant.entity.AssistantSession;

public interface AssistantSessionRepository extends JpaRepository<AssistantSession, String> {
}
