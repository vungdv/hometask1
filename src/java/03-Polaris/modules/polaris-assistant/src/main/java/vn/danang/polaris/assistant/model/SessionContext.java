package vn.danang.polaris.assistant.model;

import java.util.List;

import vn.danang.polaris.assistant.entity.AssistantMessage;

public record SessionContext(
    String sessionId,
    String userId,
    Long customerId,
    List<AssistantMessage> history,
    String userPrompt,
    String operatorId,
    boolean isStaff
) {
    public SessionContext(
            String sessionId,
            String userId,
            Long customerId,
            List<AssistantMessage> history,
            String userPrompt) {
        this(sessionId, userId, customerId, history, userPrompt, null, false);
    }
}
