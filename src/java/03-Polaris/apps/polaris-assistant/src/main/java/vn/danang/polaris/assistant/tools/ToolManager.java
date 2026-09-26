package vn.danang.polaris.assistant.tools;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * Hub contract for Model Context Protocol (MCP) operations.
 * Manages tool discovery and handles execution loops for tool calls proposed by AI models.
 */
public interface ToolManager {

    /**
     * Discovers all available tools registered with MCP providers.
     *
     * @return list of available tools
     */
    List<Tool> discoverAllTools();

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
}
