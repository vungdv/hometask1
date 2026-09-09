package vn.danang.polaris.assistant.tool;

import java.util.Map;

import vn.danang.polaris.assistant.model.SessionContext;

public interface AssistantTool {

    ToolDefinition getDefinition();

    ToolExecutionResult execute(Map<String, Object> arguments, SessionContext context);

    default String getName() {
        return getDefinition().name();
    }
}
