package vn.danang.polaris.assistant.model;

import java.util.Map;

import vn.danang.polaris.assistant.dto.AssistantDraftResponse;

public sealed interface ModelEvent permits
        ModelEvent.ThoughtEvent,
        ModelEvent.TokenDeltaEvent,
        ModelEvent.ToolCallRequestEvent,
        ModelEvent.WidgetEvent,
        ModelEvent.DraftEvent,
        ModelEvent.DoneEvent,
        ModelEvent.ErrorEvent {

    record ThoughtEvent(String thought) implements ModelEvent {}

    record TokenDeltaEvent(String delta) implements ModelEvent {}

    record ToolCallRequestEvent(String toolCallId, String toolName, Map<String, Object> arguments) implements ModelEvent {}

    record WidgetEvent(String widgetType, Object payload) implements ModelEvent {}

    record DraftEvent(AssistantDraftResponse draft) implements ModelEvent {}

    record DoneEvent(String finishReason) implements ModelEvent {}

    record ErrorEvent(String error, String remedy) implements ModelEvent {}
}
