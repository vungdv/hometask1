package vn.danang.polaris.assistant.model;

import java.util.List;

import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Contract for forwarding conversation messages to an external Foundation AI Model.
 */
public interface AssistantModelClient {

    /**
     * Send conversation messages to the AI Model and receive its response.
     *
     * @param messages conversation messages in this session including the latest user prompt
     * @return AI model text response
     */
    String chat(List<AssistantMessage> messages);
}

