package vn.danang.polaris.assistant.model;

import java.util.List;

import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Contract for forwarding conversation messages to an external Foundation AI Model.
 */
public interface AssistantModelClient {

    /**
     * Send conversation history and the latest user prompt to the AI Model and receive its response.
     *
     * @param conversationHistory prior messages in this session
     * @param latestMessage the current user prompt
     * @return AI model text response
     */
    String chat(List<AssistantMessage> conversationHistory, String latestMessage);
}
