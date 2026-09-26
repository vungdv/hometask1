package vn.danang.polaris.assistant.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.intent.IntentResolver;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolManager;
import vn.danang.polaris.assistant.tools.ToolResult;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * Facade that orchestrates tool discovery from {@link ToolManager}, intent classification via {@link IntentResolver}
 * Consolidates the intent and tool resolution workflow into a single unified operation.
 */
@Component
public class IntentResolutionFacade {

    private final IntentResolver intentResolver;
    private final ToolManager toolManager;

    @Autowired
    public IntentResolutionFacade(
            IntentResolver intentResolver,
            ToolManager toolManager) {
        this.intentResolver = intentResolver;
        this.toolManager = toolManager;
    }

    /**
     * Resolves the user intent and computes the accepted tools using the configured ToolManager.
     *
     * @param messageText the incoming user prompt
     * @param history current conversation history
     * @return ResolvedIntent containing resolved intent metadata and accepted tools
     */
    public ResolvedIntent resolve(String messageText, List<AssistantMessage> history) {
        List<AssistantMessage> context = history != null ? new ArrayList<>(history) : new ArrayList<>();
        List<Tool> availableTools = this.toolManager != null ? this.toolManager.discoverAllTools() : List.of();
        return this.intentResolver.resolve(messageText, context, availableTools);
    }

    /**
     * Executes the proposed tool calls through the configured {@link ToolManager},
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
        if (this.toolManager == null) {
            return List.of();
        }
        return this.toolManager.handleToolCalls(toolCalls, context);
    }
}
