package vn.danang.polaris.assistant.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.SessionStatus;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Service
@Transactional
public class AssistantSessionService {

    private final AssistantSessionRepository sessionRepository;

    public AssistantSessionService(AssistantSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public AssistantSession createSession(String userId, Long customerId) {
        AssistantSession session = new AssistantSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setCustomerId(customerId);
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setVersion(0L);
        return sessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public AssistantSession getSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Assistant session not found with id: " + sessionId));
    }

    public AssistantSession closeSession(String sessionId) {
        AssistantSession session = getSession(sessionId);
        if (session.getStatus() != SessionStatus.CLOSED) {
            session.setStatus(SessionStatus.CLOSED);
            session.setUpdatedAt(Instant.now());
            session = sessionRepository.save(session);
        }
        return session;
    }
}
