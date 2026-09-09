package vn.danang.polaris.assistant.tool;

import vn.danang.polaris.assistant.dto.AssistantDraftResponse;

public record ToolExecutionResult(
    String toolName,
    boolean success,
    Object output,
    String error,
    String widgetType,
    Object widgetPayload,
    AssistantDraftResponse draftPayload
) {
    public static ToolExecutionResult success(String toolName, Object output) {
        return new ToolExecutionResult(toolName, true, output, null, null, null, null);
    }

    public static ToolExecutionResult successWithWidget(String toolName, Object output, String widgetType, Object widgetPayload) {
        return new ToolExecutionResult(toolName, true, output, null, widgetType, widgetPayload, null);
    }

    public static ToolExecutionResult successWithDraft(String toolName, Object output, AssistantDraftResponse draft) {
        return new ToolExecutionResult(toolName, true, output, null, "DRAFT_CARD", draft, draft);
    }

    public static ToolExecutionResult failure(String toolName, String error) {
        return new ToolExecutionResult(toolName, false, null, error, null, null, null);
    }

    public static ToolExecutionResult failureWithWidget(String toolName, String error, String widgetType, Object widgetPayload) {
        return new ToolExecutionResult(toolName, false, null, error, widgetType, widgetPayload, null);
    }
}
