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
     * Handles the entire execution loop for a batch of tool calls proposed by the AI model.
     * Performs defensive validation against intent, policy authorization against caller scopes,
     * tool dispatching via MCP, decision auditing, telemetry events, and turns construction.
     *
     * @param toolCalls the list of tool calls proposed by the model
     * @param context the execution context containing session, intent, policy, and tracing details
     * @return result containing generated conversation messages and policy denial status
     */
    ToolExecutionResult handleToolCalls(List<ToolCall> toolCalls, ToolExecutionContext context);

    /**
     * Alias for {@link #handleToolCalls(List, ToolExecutionContext)}.
     */
    default ToolExecutionResult executeToolCalls(List<ToolCall> toolCalls, ToolExecutionContext context) {
        return handleToolCalls(toolCalls, context);
    }
}
