package vn.danang.polaris.assistant.tools;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * Represents the execution outcome of an individual {@link ToolCall}.
 *
 * @param toolCall the tool call requested by the model
 * @param result the text result or explanation from the tool execution
 * @param status the outcome status of the execution (SUCCESS, DENIED, ERROR)
 * @param errorDescription a semantic note explaining the failure context when the tool execution is not successful, or null if successful
 * @param actions structured remedy actions for an insufficient-stock rejection (WO-020's canonical
 *                field; {@code null}/absent for every result that isn't a structured remedy —
 *                WO-022 reuses this field verbatim for {@code place_order}'s rejection path)
 * @param data structured payload accompanying a successful local-tool result that isn't plain text
 *             (WO-020: the staged {@code AssistantOrderDraft} summary for {@code stage_order_draft},
 *             consumed by {@code AssistantChatService#streamTurn} to build the SSE {@code draft}
 *             event) — {@code null} for every other result
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        @JsonProperty("toolCall") ToolCall toolCall,
        @JsonProperty("result") String result,
        @JsonProperty("status") Status status,
        @JsonProperty("error-description")
        @JsonAlias({"error_description", "errorDescription"})
        String errorDescription,
        @JsonProperty("actions") List<Map<String, Object>> actions,
        @JsonProperty("data") Map<String, Object> data
) {

    public enum Status {
        SUCCESS,
        DENIED,
        ERROR
    }

    public ToolResult(ToolCall toolCall, String result, Status status) {
        this(toolCall, result, status, null, null, null);
    }

    public ToolResult(ToolCall toolCall, String result, Status status, String errorDescription) {
        this(toolCall, result, status, errorDescription, null, null);
    }

    public ToolResult {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (result == null) {
            result = "";
        }
        if (status != Status.SUCCESS) {
            if (errorDescription == null || errorDescription.isBlank()) {
                errorDescription = defaultSemanticNote(status, toolCall, result);
            }
        } else {
            errorDescription = null;
        }
    }

    private static String defaultSemanticNote(Status status, ToolCall toolCall, String result) {
        String toolName = (toolCall.name() != null && !toolCall.name().isBlank()) ? toolCall.name() : "unnamed_tool";
        if (status == Status.DENIED) {
            if (result != null && !result.isBlank()) {
                return "Execution of tool '" + toolName + "' was denied: " + result;
            }
            return "Execution of tool '" + toolName + "' was denied by policy or intent validation.";
        }
        if (status == Status.ERROR) {
            if (result != null && !result.isBlank()) {
                return "Execution of tool '" + toolName + "' failed: " + result;
            }
            return "Execution of tool '" + toolName + "' encountered an error.";
        }
        return "Tool execution did not succeed.";
    }

    public static ToolResult success(ToolCall toolCall, String result) {
        return new ToolResult(toolCall, result, Status.SUCCESS, null, null, null);
    }

    /** SUCCESS carrying a structured {@code data} payload (e.g. a staged draft summary). */
    public static ToolResult success(ToolCall toolCall, String result, Map<String, Object> data) {
        return new ToolResult(toolCall, result, Status.SUCCESS, null, null, data);
    }

    public static ToolResult denied(ToolCall toolCall, String reason) {
        return new ToolResult(toolCall, reason, Status.DENIED, null, null, null);
    }

    public static ToolResult denied(ToolCall toolCall, String reason, String errorDescription) {
        return new ToolResult(toolCall, reason, Status.DENIED, errorDescription, null, null);
    }

    public static ToolResult error(ToolCall toolCall, String error) {
        return new ToolResult(toolCall, error, Status.ERROR, null, null, null);
    }

    public static ToolResult error(ToolCall toolCall, String error, String errorDescription) {
        return new ToolResult(toolCall, error, Status.ERROR, errorDescription, null, null);
    }

    /** ERROR carrying structured remedy {@code actions[]} (the insufficient-stock rejection shape). */
    public static ToolResult error(ToolCall toolCall, String error, String errorDescription, List<Map<String, Object>> actions) {
        return new ToolResult(toolCall, error, Status.ERROR, errorDescription, actions, null);
    }

    /** ERROR carrying both {@code actions[]} and the rest of the problem shape (type/sku/quantities/remedy). */
    public static ToolResult error(ToolCall toolCall, String error, String errorDescription,
            List<Map<String, Object>> actions, Map<String, Object> data) {
        return new ToolResult(toolCall, error, Status.ERROR, errorDescription, actions, data);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    @JsonIgnore
    public boolean isDenied() {
        return status == Status.DENIED;
    }

    @JsonIgnore
    public boolean isError() {
        return status == Status.ERROR;
    }
}
