package vn.danang.polaris.assistant.model;

import java.util.Map;

import vn.danang.polaris.assistant.dto.AssistantDraftResponse;

@FunctionalInterface
public interface ModelStreamListener {

    void onEvent(ModelEvent event);

    default void onThought(String thought) {
        onEvent(new ModelEvent.ThoughtEvent(thought));
    }

    default void onToken(String delta) {
        onEvent(new ModelEvent.TokenDeltaEvent(delta));
    }

    default void onToolCall(String toolCallId, String toolName, Map<String, Object> arguments) {
        onEvent(new ModelEvent.ToolCallRequestEvent(toolCallId, toolName, arguments));
    }

    default void onWidget(String widgetType, Object payload) {
        onEvent(new ModelEvent.WidgetEvent(widgetType, payload));
    }

    default void onDraft(AssistantDraftResponse draft) {
        onEvent(new ModelEvent.DraftEvent(draft));
    }

    default void onDone(String finishReason) {
        onEvent(new ModelEvent.DoneEvent(finishReason));
    }

    default void onError(String error, String remedy) {
        onEvent(new ModelEvent.ErrorEvent(error, remedy));
    }
}
