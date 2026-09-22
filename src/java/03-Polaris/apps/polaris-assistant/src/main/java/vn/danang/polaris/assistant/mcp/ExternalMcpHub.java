package vn.danang.polaris.assistant.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.intent.DefaultPolicyEngine;
import vn.danang.polaris.assistant.intent.IntentToolRegistry;
import vn.danang.polaris.assistant.intent.PolicyDecision;
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.model.ToolCall;

/**
 * Hub and tool registry for Polaris Assistant.
 * Implements {@link McpHub} to discover tools and handle the tool call execution loop,
 * dispatching tool calls directly to Polaris Core via {@link PolarisMcpClient}
 * with policy validation and concurrent execution for remote tool calls.
 */
@Component
public class ExternalMcpHub implements McpHub, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExternalMcpHub.class);

    private final PolarisMcpClient polarisMcpClient;
    private final IntentToolRegistry intentToolRegistry;
    private final PolicyEngine policyEngine;
    private final Executor executor;
    private final boolean managedExecutor;

    @Autowired
    public ExternalMcpHub(
            PolarisMcpClient polarisMcpClient,
            ObjectProvider<IntentToolRegistry> intentToolRegistryProvider,
            ObjectProvider<PolicyEngine> policyEngineProvider,
            ObjectProvider<Executor> executorProvider) {
        this.polarisMcpClient = polarisMcpClient;
        this.intentToolRegistry = intentToolRegistryProvider != null && intentToolRegistryProvider.getIfAvailable() != null
                ? intentToolRegistryProvider.getIfAvailable()
                : new IntentToolRegistry();
        this.policyEngine = policyEngineProvider != null && policyEngineProvider.getIfAvailable() != null
                ? policyEngineProvider.getIfAvailable()
                : new DefaultPolicyEngine();
        if (executorProvider != null && executorProvider.getIfAvailable() != null) {
            this.executor = executorProvider.getIfAvailable();
            this.managedExecutor = false;
        } else {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.managedExecutor = true;
        }
    }

    public ExternalMcpHub(PolarisMcpClient polarisMcpClient) {
        this(polarisMcpClient, (IntentToolRegistry) null, null, null);
    }

    public ExternalMcpHub(
            PolarisMcpClient polarisMcpClient,
            @Nullable IntentToolRegistry intentToolRegistry,
            @Nullable PolicyEngine policyEngine) {
        this(polarisMcpClient, intentToolRegistry, policyEngine, null);
    }

    public ExternalMcpHub(
            PolarisMcpClient polarisMcpClient,
            @Nullable ObjectMapper objectMapper,
            @Nullable IntentToolRegistry intentToolRegistry,
            @Nullable PolicyEngine policyEngine) {
        this(polarisMcpClient, intentToolRegistry, policyEngine, null);
    }

    public ExternalMcpHub(
            PolarisMcpClient polarisMcpClient,
            @Nullable IntentToolRegistry intentToolRegistry,
            @Nullable PolicyEngine policyEngine,
            @Nullable Executor executor) {
        this.polarisMcpClient = polarisMcpClient;
        this.intentToolRegistry = intentToolRegistry != null
                ? intentToolRegistry
                : new IntentToolRegistry();
        this.policyEngine = policyEngine != null
                ? policyEngine
                : new DefaultPolicyEngine();
        if (executor != null) {
            this.executor = executor;
            this.managedExecutor = false;
        } else {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.managedExecutor = true;
        }
    }

    public ExternalMcpHub(
            PolarisMcpClient polarisMcpClient,
            @Nullable ObjectMapper objectMapper,
            @Nullable IntentToolRegistry intentToolRegistry,
            @Nullable PolicyEngine policyEngine,
            @Nullable Executor executor) {
        this(polarisMcpClient, intentToolRegistry, policyEngine, executor);
    }

    @Override
    public void destroy() {
        if (this.managedExecutor && this.executor instanceof ExecutorService es && !es.isShutdown()) {
            es.shutdown();
        }
    }

    /**
     * Discovers all tools available from Polaris Core.
     */
    @Override
    public List<Tool> discoverAllTools() {
        return polarisMcpClient.listAvailableTools();
    }

    /**
     * Orchestrator function for handling proposed tool calls.
     * Uses a stream pipeline to process each tool in functional style:
     * - step-1: mark done for any tool that violates policy
     * - step-2: for valid tools, execute them concurrently
     * - last step: collect results.
     */
    @Override
    public List<ToolResult> handleToolCalls(List<ToolCall> toolCalls, ToolExecutionContext context) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        Executor delegatingExecutor = new DelegatingSecurityContextExecutor(
                this.executor, SecurityContextHolder.getContext()
        );

        // use stream to build a pipeline to process each tool:
        // step-1: policy evaluation
        // step-2: if it's ok pass to execute it concurrently; otherwise mark done
        List<CompletableFuture<ToolResult>> futures =
                toolCalls.stream()
                        // step-1: policy evaluation
                        .map(toolCall -> checkPolicy(toolCall, context))
                        // step-2: if it's ok pass to execute it concurrently; otherwise mark done
                        .map(checkResult ->
                                checkResult.isOk()?
                                executeConcurrently(checkResult.toolCall(), delegatingExecutor)
                                : CompletableFuture.completedFuture(checkResult.rejection()))
                        .toList();

        // last step: collect results.
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException("Concurrent tool execution failed", cause);
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * Dispatches a tool invocation to Polaris Core.
     */
    @Override
    public CallToolResult executeTool(String toolName, Map<String, Object> arguments) {
        log.info("Dispatching execution for tool: {}", toolName);
        return polarisMcpClient.callTool(toolName, arguments);
    }

    /**
     * Policy check function: evaluates intent validity and policy authorization for a proposed tool call.
     *
     * @param toolCall the tool call proposed by the model
     * @param context execution context with session, caller, and intent metadata
     * @return {@link ToolPolicyCheckResult} indicating whether the check passed or was rejected
     */
    public ToolPolicyCheckResult checkPolicy(
            ToolCall toolCall,
            @Nullable ToolExecutionContext context) {
        if (toolCall == null) {
            ToolCall nullCall = new ToolCall("unknown", Map.of());
            return ToolPolicyCheckResult.reject(nullCall, ToolResult.error(nullCall, "Tool call cannot be null", "Invalid tool call"));
        }

        String userId = context != null && context.userId() != null ? context.userId() : "anonymous";
        String intentId = context != null && context.intentId() != null ? context.intentId() : "general.conversation";
        boolean meetsThreshold = context != null && context.meetsThreshold();
        List<Tool> filteredTools = context != null && context.filteredTools() != null ? context.filteredTools() : List.of();

        log.info("Model requested tool call: '{}' with arguments: {}", toolCall.name(), toolCall.arguments());

        // 1. Defensive tool validation against intent
        boolean isValidTool = meetsThreshold
                ? this.intentToolRegistry.isValid(intentId, toolCall.name())
                : filteredTools.stream().anyMatch(t -> t.name().equals(toolCall.name()));

        if (!isValidTool) {
            log.warn("Tool '{}' is not permitted for intent '{}'", toolCall.name(), intentId);
            String correctiveMessage = "Tool execution denied: Tool '" + toolCall.name() + "' is not permitted for intent '" + intentId + "'. Please provide a direct response or use permitted tools.";
            String semanticNote = "Tool '" + toolCall.name() + "' is not permitted under intent '" + intentId + "'.";
            return ToolPolicyCheckResult.reject(toolCall, ToolResult.error(toolCall, correctiveMessage, semanticNote));
        }

        // 2. Policy engine authorization check
        String requiredScope = this.intentToolRegistry.getRequiredScope(toolCall.name());
        PolicyDecision decision = this.policyEngine.authorize(userId, requiredScope);
        if (!decision.allowed()) {
            log.warn("Policy DENIED execution of tool '{}' for user '{}': {}", toolCall.name(), userId, decision.reason());
            String denialReason = decision.reason();
            String semanticNote = "Policy authorization denied execution of tool '" + toolCall.name() + "' for user '" + userId + "': " + denialReason;
            return ToolPolicyCheckResult.reject(toolCall, ToolResult.denied(toolCall, denialReason, semanticNote));
        }

        return ToolPolicyCheckResult.ok(toolCall);
    }

    private CompletableFuture<ToolResult> executeConcurrently(
            ToolCall toolCall,
            Executor executor) {
        return CompletableFuture.supplyAsync(() -> executeRemoteToolCall(toolCall), executor);
    }

    private ToolResult executeRemoteToolCall(ToolCall toolCall) {
        CallToolResult toolResult = executeTool(toolCall.name(), toolCall.arguments());
        String resultText = extractToolResultText(toolResult);

        if (toolResult != null && Boolean.TRUE.equals(toolResult.isError())) {
            String semanticNote = "MCP provider reported execution error for tool '" + toolCall.name() + "'.";
            return ToolResult.error(toolCall, resultText, semanticNote);
        } else {
            return ToolResult.success(toolCall, resultText);
        }
    }

    private String extractToolResultText(CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof TextContent textContent) {
                sb.append(textContent.text());
            } else if (content != null) {
                sb.append(content.toString());
            }
        }
        return sb.toString();
    }
}
