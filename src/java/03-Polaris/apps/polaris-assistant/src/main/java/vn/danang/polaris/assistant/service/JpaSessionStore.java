package vn.danang.polaris.assistant.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;

/**
 * {@link SessionStore} backed by {@code assistant_sessions} / {@code assistant_messages}.
 * Each call is its own short transaction, so no connection is held while the model is thinking.
 */
@Component
public class JpaSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JpaSessionStore.class);

    private final AssistantSessionRepository sessionRepository;
    private final AssistantMessageRepository messageRepository;
    private final Clock clock;

    @Autowired
    public JpaSessionStore(AssistantSessionRepository sessionRepository, AssistantMessageRepository messageRepository) {
        this(sessionRepository, messageRepository, Clock.systemUTC());
    }

    JpaSessionStore(AssistantSessionRepository sessionRepository, AssistantMessageRepository messageRepository, Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    @Transactional
    public List<AssistantMessage> loadHistory(String sessionId, String userId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        AssistantSession session = sessionRepository.findById(sessionId)
                .orElseGet(() -> openSession(sessionId, userId));
        requireOwner(session, userId);
        return new ArrayList<>(messageRepository.findBySessionIdOrderByIdAsc(sessionId));
    }

    @Override
    @Transactional
    public void append(String sessionId, String userId, List<AssistantMessage> messages) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (messages == null || messages.isEmpty()) {
            return;
        }
        AssistantSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Assistant session " + sessionId + " was not opened."));
        requireOwner(session, userId);
        messages.forEach(message -> message.setSessionId(sessionId));
        messageRepository.saveAll(messages);
    }

    private AssistantSession openSession(String sessionId, String userId) {
        log.info("Opening assistant session sessionId: {}, userId: {}", sessionId, userId);
        return sessionRepository.save(AssistantSession.open(sessionId, userId, clock.instant()));
    }

    private static void requireOwner(AssistantSession session, String userId) {
        if (!session.isOwnedBy(userId)) {
            log.warn("Denied access to assistant session sessionId: {}, userId: {}", session.getId(), userId);
            throw new SessionAccessDeniedException(session.getId());
        }
    }
}
