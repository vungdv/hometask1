package vn.danang.polaris.assistant.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import vn.danang.polaris.assistant.intent.DefaultIntentManager;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.IntentManager;
import vn.danang.polaris.assistant.intent.IntentToolPolicy;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.policy.DefaultPolicyEngine;
import vn.danang.polaris.assistant.policy.PolicyDecision;
import vn.danang.polaris.assistant.policy.PolicyEngine;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;

/**
 * Hub and tool registry for Polaris Assistant.
 * Implements {@link ToolManager} to discover tools and handle the tool call execution loop,
 * dispatching tool calls directly to Polaris Core via {@link PolarisMcpClient}
 * with policy validation and concurrent execution for remote tool calls.
 * Authorization is per tool: each tool's required scope is derived from the intent taxonomy
 * ({@link IntentToolPolicy}), so a low-confidence or empty intent can't skip the scope check.
 * <p>
 * {@link LocalTool}s (implemented inside the assistant, e.g. {@code stage_order_draft}) are listed next to
 * the remote tools and pass the very same {@link #checkPolicy} gate; only the dispatch differs, and a local
 * tool takes precedence over a remote tool of the same name.
 * <p>
 * {@link #ORCHESTRATOR_ONLY_TOOLS} (e.g. {@code place_order}) are never offered to the model and are rejected
 * if it names them, whatever the intent taxonomy says: an order is placed only by the confirm endpoint after
 * the shopper's click (ADR-0004 §1.B).
 */
@Component
public class PolicyToolManager implements ToolManager, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PolicyToolManager.class);

    /** Domain writes only the orchestrator may call, never the model (deny-list, checked before the taxonomy). */
    public static final Set<String> ORCHESTRATOR_ONLY_TOOLS = Set.of("place_order", "cancel_order");

    private final PolarisMcpClient polarisMcpClient;
    private final PolicyEngine policyEngine;
    private final IntentManager intentManager;
    private final Executor executor;
    private final boolean managedExecutor;
    private final Map<String, LocalTool> localTools;

    @Autowired
    public PolicyToolManager(
            PolarisMcpClient polarisMcpClient,
            ObjectProvider<PolicyEngine> policyEngineProvider,
            ObjectProvider<Executor> executorProvider,
            ObjectProvider<IntentManager> intentManagerProvider,
            ObjectProvider<LocalTool> localToolsProvider) {
        this.polarisMcpClient = polarisMcpClient;
        this.localTools = indexByName(localToolsProvider != null ? localToolsProvider.orderedStream().toList() : List.of());
        this.policyEngine = policyEngineProvider != null && policyEngineProvider.getIfAvailable() != null
                ? policyEngineProvider.getIfAvailable()
                : new DefaultPolicyEngine();
        this.intentManager = intentManagerProvider != null && intentManagerProvider.getIfAvailable() != null
                ? intentManagerProvider.getIfAvailable()
                : new DefaultIntentManager();
        if (executorProvider != null && executorProvider.getIfAvailable() != null) {
            this.executor = executorProvider.getIfAvailable();
            this.managedExecutor = false;
        } else {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.managedExecutor = true;
        }
    }

    public PolicyToolManager(
            PolarisMcpClient polarisMcpClient,
            ObjectProvider<PolicyEngine> policyEngineProvider,
            ObjectProvider<Executor> executorProvider,
            ObjectProvider<IntentManager> intentManagerProvider) {
        this(polarisMcpClient, policyEngineProvider, executorProvider, intentManagerProvider, null);
    }

    public PolicyToolManager(PolarisMcpClient polarisMcpClient) {
        this(polarisMcpClient, (PolicyEngine) null, (Executor) null, null);
    }

    public PolicyToolManager(
            PolarisMcpClient polarisMcpClient,
            @Nullable PolicyEngine policyEngine) {
        this(polarisMcpClient, policyEngine, (Executor) null, null);
    }

    public PolicyToolManager(
            PolarisMcpClient polarisMcpClient,
            @Nullable PolicyEngine policyEngine,
            @Nullable Executor executor) {
        this(polarisMcpClient, policyEngine, executor, null);
    }

    public PolicyToolManager(
            PolarisMcpClient polarisMcpClient,
            @Nullable PolicyEngine policyEngine,
            @Nullable Executor executor,
            @Nullable IntentManager intentManager) {
        this(polarisMcpClient, policyEngine, executor, intentManager, List.of());
    }

    public PolicyToolManager(
            PolarisMcpClient polarisMcpClient,
            @Nullable PolicyEngine policyEngine,
            @Nullable Executor executor,
            @Nullable IntentManager intentManager,
            @Nullable List<LocalTool> localTools) {
        this.polarisMcpClient = polarisMcpClient;
        this.localTools = indexByName(localTools != null ? localTools : List.of());
        this.policyEngine = policyEngine != null
                ? policyEngine
                : new DefaultPolicyEngine();
        this.intentManager = intentManager != null
                ? intentManager
                : new DefaultIntentManager();
        if (executor != null) {
            this.executor = executor;
            this.managedExecutor = false;
        } else {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.managedExecutor = true;
        }
    }

    private static Map<String, LocalTool> indexByName(List<LocalTool> tools) {
        Map<String, LocalTool> byName = new LinkedHashMap<>();
        for (LocalTool tool : tools) {
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate local tool name: " + tool.name());
            }
        }
        return Collections.unmodifiableMap(byName);
    }

    @Override
    public void destroy() {
        if (this.managedExecutor && this.executor instanceof ExecutorService es && !es.isShutdown()) {
            es.shutdown();
        }
    }

    /**
     * Discovers all tools available from Polaris Core, plus the assistant's own {@link LocalTool}s
     * (which replace a remote tool of the same name).
     */
    @Override
    @CustomNextSpan(
            name = "mcp.polaris.discovery",
            tags = {
                    @SpanTag(key = "mcp.tool_count", expression = "#result?.size()")
            }
    )
    public List<Tool> discoverAllTools() {
        List<Tool> remote = polarisMcpClient.listAvailableTools();
        List<Tool> tools = new ArrayList<>();
        if (remote != null) {
            for (Tool tool : remote) {
                if (ORCHESTRATOR_ONLY_TOOLS.contains(tool.name())) {
                    continue;
                }
                if (localTools.containsKey(tool.name())) {
                    log.warn("Local tool '{}' shadows a remote MCP tool of the same name; the remote tool is not offered", tool.name());
                } else {
                    tools.add(tool);
                }
            }
        }
        localTools.values().forEach(local -> tools.add(local.definition()));
        return tools;
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

        // Pipeline per tool call, results kept in call order:
        // step-1: policy evaluation
        // step-2: if it's ok, execute it; otherwise mark done. Remote calls run concurrently, while
        //         mutating local tools (e.g. stage then discard a draft) run one after another in call
        //         order, so their effects, and the cards they return, follow the order the model asked for.
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>(toolCalls.size());
        CompletableFuture<ToolResult> lastMutation = CompletableFuture.completedFuture(null);
        for (ToolCall toolCall : toolCalls) {
            ToolPolicyCheckResult checkResult = checkPolicy(toolCall, context);
            if (!checkResult.isOk()) {
                futures.add(CompletableFuture.completedFuture(checkResult.rejection()));
                continue;
            }
            LocalTool localTool = localTools.get(checkResult.toolCall().name());
            if (localTool != null && localTool.mutating()) {
                // executeLocalToolCall never completes exceptionally, so the chain always continues
                lastMutation = lastMutation.thenApplyAsync(
                        previous -> executeLocalToolCall(localTool, checkResult.toolCall(), context), delegatingExecutor);
                futures.add(lastMutation);
            } else {
                futures.add(executeConcurrently(checkResult.toolCall(), context, delegatingExecutor));
            }
        }

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

        // 0. Orchestrator-only tools are out of the model's reach under any intent
        if (ORCHESTRATOR_ONLY_TOOLS.contains(toolCall.name())) {
            log.warn("Denied model call of orchestrator-only tool '{}' for user '{}' under intent '{}'", toolCall.name(), userId, intentId);
            String reason = "Tool '" + toolCall.name() + "' is not available to the assistant: an order is placed or cancelled only by "
                    + "the shopper's explicit action outside the chat (e.g. 'Submit Order' on a draft card). "
                    + "To prepare an order, use stage_order_draft.";
            String semanticNote = "Tool '" + toolCall.name() + "' is orchestrator-only and never executed for the model.";
            return ToolPolicyCheckResult.reject(toolCall, ToolResult.denied(toolCall, reason, semanticNote));
        }

        List<IntentDefinition> intents = this.intentManager.listIntents();

        // 1. Defensive tool validation against intent; below threshold only read-only tools are valid
        boolean isValidTool;
        if (!meetsThreshold) {
            isValidTool = IntentToolPolicy.readOnlyTools(intents).contains(toolCall.name());
        } else if (intentDef != null) {
            isValidTool = intentDef.allowedTools() != null && intentDef.allowedTools().contains(toolCall.name());
        } else {
            isValidTool = filteredTools.stream().anyMatch(t -> t.name().equals(toolCall.name()));
        }

        if (!isValidTool) {
            log.warn("Tool '{}' is not permitted for intent '{}'", toolCall.name(), intentId);
            String correctiveMessage = "Tool execution denied: Tool '" + toolCall.name() + "' is not permitted for intent '" + intentId + "'. Please provide a direct response or use permitted tools.";
            String semanticNote = "Tool '" + toolCall.name() + "' is not permitted under intent '" + intentId + "'.";
            return ToolPolicyCheckResult.reject(toolCall, ToolResult.error(toolCall, correctiveMessage, semanticNote));
        }

        // 2. Policy engine authorization: the tool's own scope(s), plus the matched intent's scope
        Set<String> requiredScopes = new LinkedHashSet<>(IntentToolPolicy.requiredScopes(toolCall.name(), intents));
        if (meetsThreshold && intentDef != null
                && intentDef.requiredScope() != null && !intentDef.requiredScope().isBlank()) {
            requiredScopes.add(intentDef.requiredScope());
        }
        if (requiredScopes.isEmpty()) {
            // fail closed: a tool without a registered scope is never executed
            String denialReason = "No required scope is registered for tool '" + toolCall.name() + "'.";
            return deny(toolCall, userId, denialReason);
        }
        for (String requiredScope : requiredScopes) {
            PolicyDecision decision = this.policyEngine.authorize(requiredScope);
            if (!decision.allowed()) {
                return deny(toolCall, userId, decision.reason());
            }
        }

        return ToolPolicyCheckResult.ok(toolCall);
    }

    private ToolPolicyCheckResult deny(ToolCall toolCall, String userId, String denialReason) {
        log.warn("Policy DENIED execution of tool '{}' for user '{}': {}", toolCall.name(), userId, denialReason);
        String semanticNote = "Policy authorization denied execution of tool '" + toolCall.name() + "' for user '" + userId + "': " + denialReason;
        return ToolPolicyCheckResult.reject(toolCall, ToolResult.denied(toolCall, denialReason, semanticNote));
    }

    private CompletableFuture<ToolResult> executeConcurrently(
            ToolCall toolCall,
            @Nullable ToolExecutionContext context,
            Executor executor) {
        LocalTool localTool = localTools.get(toolCall.name());
        if (localTool != null) {
            return CompletableFuture.supplyAsync(() -> executeLocalToolCall(localTool, toolCall, context), executor);
        }
        return CompletableFuture.supplyAsync(() -> executeRemoteToolCall(toolCall), executor);
    }

    private ToolResult executeLocalToolCall(LocalTool localTool, ToolCall toolCall, @Nullable ToolExecutionContext context) {
        log.info("Dispatching execution for local tool: {}", toolCall.name());
        try {
            ToolResult result = localTool.execute(toolCall, context);
            return result != null
                    ? result
                    : ToolResult.error(toolCall, "Null result returned from tool: " + toolCall.name(), "Tool execution failure");
        } catch (Exception ex) {
            log.error("Local tool execution error for '{}': {}", toolCall.name(), ex.getMessage(), ex);
            return ToolResult.error(toolCall, "Tool execution error: " + ex.getMessage(), ex.getMessage());
        }
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

    private CallToolResult executeTool(String toolName, Map<String, Object> arguments) {
        log.info("Dispatching execution for tool: {}", toolName);
        return polarisMcpClient.callTool(toolName, arguments);
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
