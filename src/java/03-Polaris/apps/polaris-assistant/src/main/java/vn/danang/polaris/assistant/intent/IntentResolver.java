package vn.danang.polaris.assistant.intent;

import java.util.List;

import vn.danang.polaris.assistant.entity.AssistantMessage;

public interface IntentResolver {

    /**
     * Resolves user intent from the latest message and previous conversation context.
     *
     * @param userMessage the raw user utterance
     * @param context conversation history in the current session
     * @return IntentClassification containing the resolved intent ID and confidence score
     */
    IntentClassification resolve(String userMessage, List<AssistantMessage> context);
}
