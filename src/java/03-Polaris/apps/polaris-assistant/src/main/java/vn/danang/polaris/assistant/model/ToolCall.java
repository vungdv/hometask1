package vn.danang.polaris.assistant.model;

import java.util.Map;

/**
 * Represents a tool call requested by the AI Foundation Model.
 *
 * @param name the unique name of the tool to execute
 * @param arguments the arguments map to pass to the tool
 */
public record ToolCall(
        String name,
        Map<String, Object> arguments
) {
    public ToolCall {
        if (arguments == null) {
            arguments = Map.of();
        }
    }
}
