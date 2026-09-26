package vn.danang.polaris.assistant.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import vn.danang.polaris.assistant.dto.OrderItemRequest;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.policy.DefaultPolicyEngine;
import vn.danang.polaris.assistant.policy.PolicyDecision;
import vn.danang.polaris.assistant.policy.PolicyEngine;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;
import vn.danang.polaris.assistant.service.DraftStagingService;
import vn.danang.polaris.assistant.service.DraftStagingService.StageOutcome;

/**
 * Hub and tool registry for Polaris Assistant.
 * Implements {@link ToolManager} to discover tools and handle the tool call execution loop,
 * dispatching tool calls directly to Polaris Core via {@link PolarisMcpClient}
 * with policy validation and concurrent execution for remote tool calls.
 */
@Component
public class PolicyToolManager implements ToolManager, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PolicyToolManager.class);

    private final PolarisMcpClient polarisMcpClient;
    private final PolicyEngine policyEngine;
    private final Executor executor;
    private final boolean managedExecutor;
    @Nullable
    private final DraftStagingService draftStagingService;

    @Autowired
    public PolicyToolManager(
            PolarisMcpClient polarisMcpClient,
            ObjectProvider<PolicyEngine> policyEngineProvider,
            ObjectProvider<Executor> executorProvider,
            ObjectProvider<DraftStagingService> draftStagingServiceProvider) {
        this.polarisMcpClient = polarisMcpClient;
        this.policyEngine = policyEngineProvider != null && policyEngineProvider.getIfAvailable() != null
                ? policyEngineProvider.getIfAvailable()
                : new DefaultPolicyEngine();
        // getIfUnique() (not getIfAvailable()): once WO-021 enables @EnableScheduling, Spring Boot
        // also registers its own "taskScheduler" bean, which is itself an Executor - making this
        // provider ambiguous rather than empty. getIfAvailable() throws on ambiguity;
        // getIfUnique() just falls through to this class's own dedicated virtual-thread executor,
        // which is the correct outcome here regardless - this component was never meant to share
        // the application's general-purpose executor, only to accept a caller-supplied override.
        Executor providedExecutor = executorProvider != null ? executorProvider.getIfUnique() : null;
        if (providedExecutor != null) {
            this.executor = providedExecutor;
            this.managedExecutor = false;
        } else {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.managedExecutor = true;
        }
        this.draftStagingService = draftStagingServiceProvider != null ? draftStagingServiceProvider.getIfAvailable() : null;
    }

    public PolicyToolManager(
            PolarisMcpClient polarisMcpClient,
            ObjectProvider<PolicyEngine> policyEngineProvider,
            ObjectProvider<Executor> executorProvider) {
        this(polarisMcpClient, policyEngineProvider, executorProvider, null);
    }

    public PolicyToolManager(PolarisMcpClient polarisMcpClient) {
        this(polarisMcpClient, (PolicyEngine) null, null, null);
    }

    public PolicyToolManager(
            PolarisMcpClient polarisMcpClient,
            @Nullable PolicyEngine policyEngine) {
        this(polarisMcpClient, policyEngine, null, null);
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
            @Nullable DraftStagingService draftStagingService) {
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
        this.draftStagingService = draftStagingService;
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
        List<Tool> tools = new ArrayList<>(polarisMcpClient.listAvailableTools());
        tools.add(StageOrderDraftTool.definition());
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

        // use stream to build a pipeline to process each tool:
        // step-1: policy evaluation
        // step-2: if it's ok pass to execute it concurrently; otherwise mark done
        List<CompletableFuture<ToolResult>> futures =
                toolCalls.stream()
                        // step-1: policy evaluation
                        .map(toolCall -> checkPolicy(toolCall, context))
                        // step-2: if it's ok pass to execute it concurrently; otherwise mark done
                        // stage_order_draft is a LOCAL tool (never dispatched to Polaris Core's MCP
                        // surface) but still runs through the same policy gate above first.
                        .map(checkResult ->
                                checkResult.isOk()?
                                (StageOrderDraftTool.TOOL_STAGE_ORDER_DRAFT.equals(checkResult.toolCall().name())
                                        ? CompletableFuture.supplyAsync(
                                                () -> executeStageOrderDraft(checkResult.toolCall(), context), delegatingExecutor)
                                        : executeConcurrently(checkResult.toolCall(), delegatingExecutor))
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
        PolicyDecision decision = this.policyEngine.authorize(requiredScope);
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

    /**
     * Executes {@code stage_order_draft} entirely in-process via {@link DraftStagingService} —
     * never through {@link #executeTool(String, Map)}/MCP dispatch. Fails closed (a {@code ERROR}
     * {@link ToolResult}, no draft mutation) on any exception, including Catalog being unreachable.
     */
    private ToolResult executeStageOrderDraft(ToolCall toolCall, @Nullable ToolExecutionContext context) {
        try {
            if (this.draftStagingService == null) {
                return ToolResult.error(toolCall, "stage_order_draft is not available in this deployment.",
                        "DraftStagingService is not configured");
            }

            Map<String, Object> args = toolCall.arguments();

            Long customerId = parseLong(args.get("customer_id") != null ? args.get("customer_id") : args.get("customerId"));
            if (customerId == null) {
                return ToolResult.error(toolCall,
                        "Please resolve the customer first via 'search_customers_by_name' and retry 'stage_order_draft' with the resolved customer_id.",
                        "stage_order_draft requires a resolved numeric customer_id; customer_name fuzzy lookup crosses into the Order context and is out of this tool's scope.");
            }

            Object rawItems = args.get("items");
            if (!(rawItems instanceof List<?> rawList) || rawList.isEmpty()) {
                return ToolResult.error(toolCall, "Parameter 'items' is required and must not be empty.", "Invalid stage_order_draft arguments");
            }

            List<OrderItemRequest> items = new ArrayList<>();
            for (Object o : rawList) {
                if (!(o instanceof Map<?, ?> itemMap)) {
                    return ToolResult.error(toolCall, "Each item must be an object with 'sku' and 'quantity'.", "Invalid stage_order_draft arguments");
                }
                Object rawSku = itemMap.get("sku");
                String sku = rawSku != null ? rawSku.toString().trim() : null;
                if (sku == null || sku.isBlank()) {
                    return ToolResult.error(toolCall, "Item 'sku' is required.", "Invalid stage_order_draft arguments");
                }
                Integer qty = parseInteger(itemMap.get("quantity"));
                if (qty == null || qty < 1) {
                    return ToolResult.error(toolCall, "Item 'quantity' must be at least 1 for SKU '" + sku + "'.", "Invalid stage_order_draft arguments");
                }
                items.add(new OrderItemRequest(sku, qty));
            }

            Object rawDraftId = args.get("draft_id") != null ? args.get("draft_id") : args.get("draftId");
            String draftId = rawDraftId != null ? rawDraftId.toString().trim() : null;
            if (draftId != null && draftId.isBlank()) {
                draftId = null;
            }

            String sessionId = context != null ? context.sessionId() : null;
            String userId = context != null && context.userId() != null ? context.userId() : "anonymous";

            StageOutcome outcome = draftStagingService.stage(sessionId, userId, customerId, draftId, items);

            if (outcome instanceof StageOutcome.Staged staged) {
                AssistantOrderDraft draft = staged.draft();
                String summary = String.format("Staged order draft %s (%d item(s), total %s), awaiting confirmation.",
                        draft.getId(), staged.items().size(), draft.getTotalAmount());
                return ToolResult.success(toolCall, summary, draftSummaryData(staged));
            }

            StageOutcome.Rejected rejected = (StageOutcome.Rejected) outcome;
            String humanReadableSummary = String.format(
                    "Insufficient stock for product '%s'. Requested: %d, available: %d. Remedy: Reduce order quantity for '%s' to %d or fewer units.",
                    rejected.sku(), rejected.requested(), rejected.available(), rejected.sku(), rejected.available());
            return ToolResult.error(toolCall, humanReadableSummary, "Tool execution failure: insufficient stock",
                    rejected.actions(), problemData(rejected));
        } catch (Exception ex) {
            log.error("stage_order_draft execution error: {}", ex.getMessage(), ex);
            return ToolResult.error(toolCall, "Tool execution error: " + ex.getMessage(), ex.getMessage());
        }
    }

    private Map<String, Object> problemData(StageOutcome.Rejected rejected) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "https://polaris.local/errors/out-of-stock");
        data.put("sku", rejected.sku());
        data.put("requested_quantity", rejected.requested());
        data.put("available_quantity", rejected.available());
        data.put("remedy", String.format("Reduce order quantity for '%s' to %d or fewer units.",
                rejected.sku(), rejected.available()));
        return data;
    }

    private Map<String, Object> draftSummaryData(StageOutcome.Staged staged) {
        AssistantOrderDraft draft = staged.draft();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftId", draft.getId());
        data.put("status", draft.getStatus().name());
        data.put("expiresAt", draft.getExpiresAt() != null ? draft.getExpiresAt().toString() : null);
        data.put("totalAmount", draft.getTotalAmount());
        data.put("items", staged.items().stream()
                .map(i -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sku", i.sku());
                    item.put("quantity", i.quantity());
                    item.put("unitPrice", i.unitPrice());
                    item.put("lineTotal", i.lineTotal());
                    return item;
                })
                .toList());
        return data;
    }

    private Long parseLong(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInteger(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
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
