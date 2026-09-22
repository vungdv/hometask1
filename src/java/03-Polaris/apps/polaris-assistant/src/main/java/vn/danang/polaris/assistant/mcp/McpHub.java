package vn.danang.polaris.assistant.mcp;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.model.ToolCall;

/**
 * Hub contract for Model Context Protocol (MCP) operations.
 * Manages tool discovery and handles execution loops for tool calls proposed by AI models.
 */
public interface McpHub {

    /**
     * Discovers all available tools registered with MCP providers.
     *
     * @return list of available tools
     */
    List<Tool> discoverAllTools();

    /**
     * Dispatches a single tool invocation to an MCP provider.
     *
     * @param toolName the name of the tool to execute
     * @param arguments the arguments map for the tool
     * @return result of the tool execution
     */
    CallToolResult executeTool(String toolName, Map<String, Object> arguments);

    /**
     * Handles the execution for a batch of tool calls proposed by the AI model.
     * Performs defensive validation against intent, policy authorization against caller scopes,
     * tool dispatching via MCP, decision auditing, and telemetry events.
     *
     * @param toolCalls the list of tool calls proposed by the model
     * @param context the execution context containing session, intent, policy, and tracing details
     * @return list of tool results indicating outcome for each tool call
     */
    List<ToolResult> handleToolCalls(List<ToolCall> toolCalls, ToolExecutionContext context);

    /**
     * Alias for {@link #handleToolCalls(List, ToolExecutionContext)}.
     */
    default List<ToolResult> executeToolCalls(List<ToolCall> toolCalls, ToolExecutionContext context) {
        return handleToolCalls(toolCalls, context);
    }
}
