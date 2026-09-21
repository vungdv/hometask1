package vn.danang.polaris.assistant.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.mcp.McpHub;

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
        return resolve(messageText, history, this.mcpHub);
    }

    /**
     * Resolves the user intent and computes the accepted tools using the specified McpHub.
     *
     * @param messageText the incoming user prompt
     * @param history current conversation history
     * @param hub MCP hub to discover tools from
     * @return ResolvedIntent containing resolved intent metadata and accepted tools
     */
    private ResolvedIntent resolve(String messageText, List<AssistantMessage> history, McpHub hub) {
        List<Tool> availableTools = Optional.ofNullable(hub)
                .map(McpHub::discoverAllTools)
                .orElseGet(List::of);
        return resolve(messageText, history, availableTools);
    }

    /**
     * Resolves the user intent and computes the accepted tools given pre-discovered tools.
     *
     * @param messageText the incoming user prompt
     * @param history current conversation history
     * @param availableTools pre-discovered available tools
     * @return ResolvedIntent containing resolved intent metadata and accepted tools
     */
    private ResolvedIntent resolve(String messageText, List<AssistantMessage> history, List<Tool> availableTools) {
        List<AssistantMessage> context = history != null ? new ArrayList<>(history) : new ArrayList<>();
        IntentClassification classification = intentResolver.resolve(messageText, context);
        return intentToolRegistry.resolveIntent(classification, availableTools);
    }
}
