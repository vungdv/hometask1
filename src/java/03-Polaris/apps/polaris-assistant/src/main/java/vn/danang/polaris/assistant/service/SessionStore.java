package vn.danang.polaris.assistant.service;

import java.util.List;

import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Durable conversation store: owns the assistant session and its message history, and enforces
 * that only the caller who opened a session can read or extend it.
 */
public interface SessionStore {

    /**
     * Returns the session's history, oldest first, as a mutable list. Opens the session for
     * {@code userId} if it doesn't exist yet.
     *
     * @throws SessionAccessDeniedException if the session belongs to another user
     */
    List<AssistantMessage> loadHistory(String sessionId, String userId);

    /**
     * Appends the turn's new messages to the session, in order.
     *
     * @throws SessionAccessDeniedException if the session belongs to another user
     */
    void append(String sessionId, String userId, List<AssistantMessage> messages);
}
