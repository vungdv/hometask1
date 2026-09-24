package vn.danang.polaris.assistant.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.mcp.McpHub;
import vn.danang.polaris.assistant.mcp.ToolExecutionContext;
import vn.danang.polaris.assistant.mcp.ToolResult;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * Facade that orchestrates tool discovery from {@link McpHub}, intent classification
 * via {@link IntentResolver}, and tool filtering through {@link IntentToolRegistry}.
 * Consolidates the intent and tool resolution workflow into a single unified operation.
 */
@Component
public class IntentResolutionFacade {

    private final IntentResolver intentResolver;
    private final IntentToolRegistry intentToolRegistry;
    private final McpHub mcpHub;

    @Autowired
    public IntentResolutionFacade(
            IntentResolver intentResolver,
            IntentToolRegistry intentToolRegistry,
            McpHub mcpHub) {
        this.intentResolver = intentResolver;
        this.intentToolRegistry = intentToolRegistry;
        this.mcpHub = mcpHub;
    }

    /**
     * Resolves the user intent and computes the accepted tools using the configured McpHub.
     *
     * @param messageText the incoming user prompt
     * @param history current conversation history
     * @return ResolvedIntent containing resolved intent metadata and accepted tools
     */
    public ResolvedIntent resolve(String messageText, List<AssistantMessage> history) {
        List<AssistantMessage> context = history != null ? new ArrayList<>(history) : new ArrayList<>();
        var availableTools = this.mcpHub.discoverAllTools();
        IntentClassification classification = intentResolver.resolve(messageText, context);
        return intentToolRegistry.resolveIntent(classification, availableTools);
    }

    /**
     * Executes the proposed tool calls through the configured {@link McpHub},
     * ensuring all tool invocations and accepted tools are governed by the intent and policy framework.
     *
     * @param toolCalls the tool calls proposed by the model
     * @param context the tool execution context containing session, intent, and security metadata
     * @return list of tool results representing the outcome of each tool execution
     */
    public List<ToolResult> executeToolCalls(
            List<ToolCall> toolCalls,
            ToolExecutionContext context) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        if (this.mcpHub == null) {
            return List.of();
        }
        return this.mcpHub.handleToolCalls(toolCalls, context);
    }
}
