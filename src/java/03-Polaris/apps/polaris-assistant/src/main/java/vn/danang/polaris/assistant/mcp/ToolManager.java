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

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.policy.DefaultPolicyEngine;
import vn.danang.polaris.assistant.policy.PolicyDecision;
import vn.danang.polaris.assistant.policy.PolicyEngine;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;

/**
 * Hub and tool registry for Polaris Assistant.
 * Implements {@link McpHub} to discover tools and handle the tool call execution loop,
 * dispatching tool calls directly to Polaris Core via {@link PolarisMcpClient}
 * with policy validation and concurrent execution for remote tool calls.
 */
@Component
public class ToolManager implements McpHub, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ToolManager.class);

    private final PolarisMcpClient polarisMcpClient;
    private final PolicyEngine policyEngine;
    private final Executor executor;
    private final boolean managedExecutor;

    @Autowired
    public ToolManager(
            PolarisMcpClient polarisMcpClient,
            ObjectProvider<PolicyEngine> policyEngineProvider,
            ObjectProvider<Executor> executorProvider) {
        this.polarisMcpClient = polarisMcpClient;
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

    public ToolManager(PolarisMcpClient polarisMcpClient) {
        this(polarisMcpClient, (PolicyEngine) null, null);
    }

    public ToolManager(
            PolarisMcpClient polarisMcpClient,
            @Nullable PolicyEngine policyEngine) {
        this(polarisMcpClient, policyEngine, null);
    }

    public ToolManager(
            PolarisMcpClient polarisMcpClient,
            @Nullable PolicyEngine policyEngine,
            @Nullable Executor executor) {
        this.polarisMcpClient = polarisMcpClient;
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
    @CustomNextSpan(
            name = "mcp.polaris.discovery",
            tags = {
                    @SpanTag(key = "mcp.tool_count", expression = "#result?.size()")
            }
    )
    public List<Tool> discoverAllTools() {
        return polarisMcpClient.listAvailableTools();
    }

    @Override
    @CustomNextSpan(
            name = "mcp.polaris.execute",
            tags = {
                    @SpanTag(key = "mcp.itent_id", expression = "#context?.resolvedIntent()?.intentId()"),
                    @SpanTag(key = "mcp.itent_confidence", expression = "#context?.resolvedIntent()?.confidence()"),
            },
            resultTags = {
                    @SpanTag(key = "mcp.tool_call.count", expression = "T(vn.danang.polaris.assistant.mcp.ToolResultsSummary).summarize(#result).total()"),
                    @SpanTag(key = "mcp.tool_call.success_count", expression = "T(vn.danang.polaris.assistant.mcp.ToolResultsSummary).summarize(#result).successCount()"),
                    @SpanTag(key = "mcp.tool_call.denied_count", expression = "T(vn.danang.polaris.assistant.mcp.ToolResultsSummary).summarize(#result).deniedCount()"),
                    @SpanTag(key = "mcp.tool_call.error_count", expression = "T(vn.danang.polaris.assistant.mcp.ToolResultsSummary).summarize(#result).errorCount()"),
                    @SpanTag(key = "mcp.tool_call.outcome", expression = "T(vn.danang.polaris.assistant.mcp.ToolResultsSummary).summarize(#result).outcome()"),
                    @SpanTag(key = "mcp.tool_call.failure_reason", expression = "T(vn.danang.polaris.assistant.mcp.ToolResultsSummary).summarize(#result).failureReason()"),
                    @SpanTag(key = "mcp.tool_call.failed_tools", expression = "T(vn.danang.polaris.assistant.mcp.ToolResultsSummary).summarize(#result).failedTools()"),
            }
    )
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

        List<ToolResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        logOutcome(results);
        return results;
    }

    private void logOutcome(List<ToolResult> results) {
        ToolResultsSummary summary = ToolResultsSummary.summarize(results);
        if (summary.deniedCount() > 0 || summary.errorCount() > 0) {
            log.warn("Tool call batch outcome '{}': {} succeeded, {} denied, {} error (of {}); reason: {}",
                    summary.outcome(), summary.successCount(), summary.deniedCount(), summary.errorCount(),
                    summary.total(), summary.failureReason());
        } else {
            log.info("Tool call batch outcome '{}': {}/{} succeeded",
                    summary.outcome(), summary.successCount(), summary.total());
        }
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
        ResolvedIntent resolvedIntent = context != null ? context.resolvedIntent() : null;
        String intentId = resolvedIntent != null && resolvedIntent.intentId() != null ? resolvedIntent.intentId() : "general.conversation";
        boolean meetsThreshold = resolvedIntent != null && resolvedIntent.meetsThreshold();
        List<Tool> filteredTools = resolvedIntent != null && resolvedIntent.filteredTools() != null ? resolvedIntent.filteredTools() : List.of();
        IntentDefinition intentDef = resolvedIntent != null ? resolvedIntent.intentDefinition() : null;

        log.info("Model requested tool call: '{}' with arguments: {}", toolCall.name(), toolCall.arguments());

        // 1. Defensive tool validation against intent
        boolean isValidTool;
        if (intentDef != null) {
            isValidTool = !meetsThreshold
                    || (intentDef.allowedTools() != null && intentDef.allowedTools().contains(toolCall.name()));
        } else {
            isValidTool = !meetsThreshold
                    || filteredTools.stream().anyMatch(t -> t.name().equals(toolCall.name()));
        }

        if (!isValidTool) {
            log.warn("Tool '{}' is not permitted for intent '{}'", toolCall.name(), intentId);
            String correctiveMessage = "Tool execution denied: Tool '" + toolCall.name() + "' is not permitted for intent '" + intentId + "'. Please provide a direct response or use permitted tools.";
            String semanticNote = "Tool '" + toolCall.name() + "' is not permitted under intent '" + intentId + "'.";
            return ToolPolicyCheckResult.reject(toolCall, ToolResult.error(toolCall, correctiveMessage, semanticNote));
        }

        // 2. Policy engine authorization check: check requiredScope directly on IntentDefinition
        String requiredScope = intentDef != null ? intentDef.requiredScope() : null;
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
        try {
            CallToolResult mcpResult = executeTool(toolCall.name(), toolCall.arguments());
            if (mcpResult == null) {
                return ToolResult.error(toolCall, "Null result returned from tool: " + toolCall.name(), "Tool execution failure");
            }

            if (Boolean.TRUE.equals(mcpResult.isError())) {
                String errorText = extractText(mcpResult);
                return ToolResult.error(toolCall, errorText, "Tool execution failure");
            }

            String content = extractText(mcpResult);
            return ToolResult.success(toolCall, content);
        } catch (Exception ex) {
            log.error("Tool execution error for '{}': {}", toolCall.name(), ex.getMessage(), ex);
            return ToolResult.error(toolCall, "Tool execution error: " + ex.getMessage(), ex.getMessage());
        }
    }

    private String extractText(CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        return result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .findFirst()
                .orElse("");
    }
}
