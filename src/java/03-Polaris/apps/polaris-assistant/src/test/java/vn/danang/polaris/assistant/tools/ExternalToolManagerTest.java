package vn.danang.polaris.assistant.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.policy.PolicyDecision;
import vn.danang.polaris.assistant.policy.PolicyEngine;
import vn.danang.polaris.assistant.ai.ToolCall;

@ExtendWith(MockitoExtension.class)
class ExternalToolManagerTest {

    @Mock
    private PolarisMcpClient polarisMcpClient;

    private PolicyToolManager mcpHub;

    @BeforeEach
    void setUp() {
        mcpHub = new PolicyToolManager(polarisMcpClient);
    }

    // =========================================================================
    // 1. Happy path — standard discovery, execution, and turn handling
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given registered client, when discovering tools, then delegates to PolarisMcpClient")
        void delegates_tool_discovery_to_mcp_client() {
            Tool tool = Tool.builder("search_available_products", Map.of())
                    .description("Search products")
                    .build();
            when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(tool));

            List<Tool> tools = mcpHub.discoverAllTools();

            // WO-020: discoverAllTools() also appends the local stage_order_draft tool
            // (never dispatched to PolarisMcpClient), alongside whatever MCP returns.
            assertThat(tools)
                    .extracting(Tool::name)
                    .containsExactly("search_available_products", "stage_order_draft");
            verify(polarisMcpClient, times(1)).listAvailableTools();
        }

        @Test
        @DisplayName("Given PolicyToolManager with CustomNextSpanAspect, when discovering tools, then tags span with mcp.tool_count")
        void tags_span_with_mcp_tool_count_when_discovering_tools() {
            io.micrometer.tracing.Span span = mock(io.micrometer.tracing.Span.class);
            io.micrometer.tracing.Tracer tracer = mock(io.micrometer.tracing.Tracer.class);
            io.micrometer.tracing.Tracer.SpanInScope spanInScope = mock(io.micrometer.tracing.Tracer.SpanInScope.class);
            when(tracer.nextSpan()).thenReturn(span);
            when(span.name(anyString())).thenReturn(span);
            when(span.tag(anyString(), anyString())).thenReturn(span);
            when(span.start()).thenReturn(span);
            when(tracer.withSpan(span)).thenReturn(spanInScope);

            org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                    new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(mcpHub);
            factory.setProxyTargetClass(true);
            factory.addAspect(new vn.danang.polaris.assistant.observability.trace.CustomNextSpanAspect(tracer));
            PolicyToolManager proxy = factory.getProxy();

            Tool tool1 = Tool.builder("search_available_products", Map.of()).build();
            Tool tool2 = Tool.builder("place_order", Map.of()).build();
            when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(tool1, tool2));

            List<Tool> tools = proxy.discoverAllTools();

            // 2 remote tools + the local stage_order_draft tool WO-020 adds.
            assertThat(tools).hasSize(3);
            verify(span).name("mcp.polaris.discovery");
            verify(span).tag("mcp.tool_count", "3");
            verify(span).start();
            verify(span).end();
        }

        @Test
        @DisplayName("Given permitted tool call, when handled, then executes tool and returns success ToolResult")
        void handles_permitted_tool_calls_and_returns_turn_messages() {
            CallToolResult toolResult = new CallToolResult(
                    List.of(TextContent.builder("Product found: Charger").build()),
                    false,
                    null,
                    Map.of()
            );
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(toolResult);

            Tool tool = Tool.builder("search_available_products", Map.of()).build();
            ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
            when(resolvedIntent.intentId()).thenReturn("catalog.product.search");
            when(resolvedIntent.meetsThreshold()).thenReturn(true);
            when(resolvedIntent.filteredTools()).thenReturn(List.of(tool));

            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, resolvedIntent
            );
            List<ToolCall> toolCalls = List.of(new ToolCall("search_available_products", Map.of("query", "charger"), "sig-123"));

            List<ToolResult> results = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(results).hasSize(1);
            ToolResult result = results.getFirst();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.SUCCESS);
            assertThat(result.toolCall()).isEqualTo(toolCalls.getFirst());
            assertThat(result.result()).isEqualTo("Product found: Charger");
            assertThat(result.errorDescription()).isNull();
        }

        @Test
        @DisplayName("Given valid authorized tool call, when checkPolicy evaluated, returns ok ToolPolicyCheckResult")
        void checkPolicy_returns_ok_for_valid_authorized_tool() {
            Tool tool = Tool.builder("search_available_products", Map.of()).build();
            ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
            when(resolvedIntent.intentId()).thenReturn("catalog.product.search");
            when(resolvedIntent.meetsThreshold()).thenReturn(true);
            when(resolvedIntent.filteredTools()).thenReturn(List.of(tool));

            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, resolvedIntent
            );
            ToolCall toolCall = new ToolCall("search_available_products", Map.of("query", "charger"));

            ToolPolicyCheckResult checkResult = mcpHub.checkPolicy(toolCall, context);

            assertThat(checkResult.isOk()).isTrue();
            assertThat(checkResult.isRejected()).isFalse();
            assertThat(checkResult.toolCall()).isEqualTo(toolCall);
            assertThat(checkResult.rejection()).isNull();
        }

    }

    // =========================================================================
    // 2. Invalid input & policy enforcement
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & policy enforcement")
    class InvalidInput {

        @Test
        @DisplayName("Given tool forbidden for current intent, when handled, then returns corrective error result without invoking client")
        void rejects_forbidden_tool_with_corrective_message_without_client_call() {
            Tool allowedTool = Tool.builder("search_available_products", Map.of()).build();
            ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
            when(resolvedIntent.intentId()).thenReturn("catalog.product.search");
            when(resolvedIntent.meetsThreshold()).thenReturn(true);
            when(resolvedIntent.filteredTools()).thenReturn(List.of(allowedTool));

            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, resolvedIntent
            );
            List<ToolCall> toolCalls = List.of(new ToolCall("cancel_order", Map.of("order_id", "123")));

            List<ToolResult> results = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(results).hasSize(1);
            ToolResult result = results.getFirst();
            assertThat(result.isError()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.ERROR);
            assertThat(result.result()).contains("not permitted for intent 'catalog.product.search'");
            assertThat(result.errorDescription()).isEqualTo("Tool 'cancel_order' is not permitted under intent 'catalog.product.search'.");
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }

        @Test
        @DisplayName("Given policy engine denies scope, when handled, then stops execution and returns denial reason")
        void stops_execution_and_returns_denial_when_policy_denies_scope() {
            PolicyEngine mockPolicy = mock(PolicyEngine.class);
            when(mockPolicy.authorize(eq("order.write")))
                    .thenReturn(PolicyDecision.deny("Missing scope order.write"));

            PolicyToolManager hubWithPolicy = new PolicyToolManager(polarisMcpClient, mockPolicy);

            Tool tool = Tool.builder("place_order", Map.of()).build();
            IntentDefinition intentDef = mock(IntentDefinition.class);
            when(intentDef.allowedTools()).thenReturn(List.of("place_order"));
            when(intentDef.requiredScope()).thenReturn("order.write");

            ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
            when(resolvedIntent.intentId()).thenReturn("commerce.order.place");
            when(resolvedIntent.meetsThreshold()).thenReturn(true);
            when(resolvedIntent.filteredTools()).thenReturn(List.of(tool));
            when(resolvedIntent.intentDefinition()).thenReturn(intentDef);

            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, resolvedIntent
            );
            List<ToolCall> toolCalls = List.of(new ToolCall("place_order", Map.of("sku", "PROD-1")));

            List<ToolResult> results = hubWithPolicy.handleToolCalls(toolCalls, context);

            assertThat(results).hasSize(1);
            ToolResult result = results.getFirst();
            assertThat(result.isDenied()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.DENIED);
            assertThat(result.result()).isEqualTo("Missing scope order.write");
            assertThat(result.errorDescription()).isEqualTo("Policy authorization denied execution of tool 'place_order' for user 'user-1': Missing scope order.write");
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }

        @Test
        @DisplayName("Given tool forbidden for intent, when checkPolicy evaluated, returns rejected ToolPolicyCheckResult")
        void checkPolicy_returns_rejected_for_forbidden_tool() {
            Tool allowedTool = Tool.builder("search_available_products", Map.of()).build();
            ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
            when(resolvedIntent.intentId()).thenReturn("catalog.product.search");
            when(resolvedIntent.meetsThreshold()).thenReturn(true);
            when(resolvedIntent.filteredTools()).thenReturn(List.of(allowedTool));

            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, resolvedIntent
            );
            ToolCall toolCall = new ToolCall("cancel_order", Map.of());

            ToolPolicyCheckResult checkResult = mcpHub.checkPolicy(toolCall, context);

            assertThat(checkResult.isOk()).isFalse();
            assertThat(checkResult.isRejected()).isTrue();
            assert checkResult.rejection() != null;
            assertThat(checkResult.rejection().isError()).isTrue();
        }

        @Test
        @DisplayName("Given policy engine denies scope, when checkPolicy evaluated, returns rejected ToolPolicyCheckResult with denied status")
        void checkPolicy_returns_rejected_when_policy_engine_denies() {
            PolicyEngine mockPolicy = mock(PolicyEngine.class);
            when(mockPolicy.authorize(eq("order.write")))
                    .thenReturn(PolicyDecision.deny("Missing scope order.write"));

            PolicyToolManager hubWithPolicy = new PolicyToolManager(polarisMcpClient, mockPolicy);

            Tool tool = Tool.builder("place_order", Map.of()).build();
            IntentDefinition intentDef = mock(IntentDefinition.class);
            when(intentDef.allowedTools()).thenReturn(List.of("place_order"));
            when(intentDef.requiredScope()).thenReturn("order.write");

            ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
            when(resolvedIntent.intentId()).thenReturn("commerce.order.place");
            when(resolvedIntent.meetsThreshold()).thenReturn(true);
            when(resolvedIntent.filteredTools()).thenReturn(List.of(tool));
            when(resolvedIntent.intentDefinition()).thenReturn(intentDef);

            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, resolvedIntent
            );
            ToolCall toolCall = new ToolCall("place_order", Map.of("sku", "PROD-1"));

            ToolPolicyCheckResult checkResult = hubWithPolicy.checkPolicy(toolCall, context);

            assertThat(checkResult.isOk()).isFalse();
            assertThat(checkResult.isRejected()).isTrue();
            assert checkResult.rejection() != null;
            assertThat(checkResult.rejection().isDenied()).isTrue();
            assertThat(checkResult.rejection().status()).isEqualTo(ToolResult.Status.DENIED);
            assertThat(checkResult.rejection().result()).isEqualTo("Missing scope order.write");
            assertThat(checkResult.rejection().errorDescription())
                    .isEqualTo("Policy authorization denied execution of tool 'place_order' for user 'user-1': Missing scope order.write");
        }

        @Test
        @DisplayName("Given ToolExecutionContext with custom IntentDefinition, when checkPolicy evaluated, retrieves requiredScope from IntentDefinition without registry")
        void checkPolicy_retrieves_requiredScope_from_intent_definition_in_context() {
            PolicyEngine mockPolicy = mock(PolicyEngine.class);
            when(mockPolicy.authorize(eq("custom.scope")))
                    .thenReturn(PolicyDecision.allow());

            // Construct PolicyToolManager with custom PolicyEngine
            PolicyToolManager hubWithoutRegistry = new PolicyToolManager(polarisMcpClient, mockPolicy);

            Tool tool = Tool.builder("custom_tool", Map.of()).build();
            IntentDefinition customIntent = mock(IntentDefinition.class);
            when(customIntent.allowedTools()).thenReturn(List.of("custom_tool"));
            when(customIntent.requiredScope()).thenReturn("custom.scope");

            ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
            when(resolvedIntent.intentId()).thenReturn("custom.intent");
            when(resolvedIntent.meetsThreshold()).thenReturn(true);
            when(resolvedIntent.filteredTools()).thenReturn(List.of(tool));
            when(resolvedIntent.intentDefinition()).thenReturn(customIntent);

            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-42", "user-42", 1, resolvedIntent
            );
            ToolCall toolCall = new ToolCall("custom_tool", Map.of());

            ToolPolicyCheckResult checkResult = hubWithoutRegistry.checkPolicy(toolCall, context);

            assertThat(checkResult.isOk()).isTrue();
            verify(mockPolicy).authorize("custom.scope");
        }

        @Test
        @DisplayName("Given ToolExecutionContext with mocked ResolvedIntent, retrieves scope from IntentDefinition correctly")
        void checkPolicy_retrieves_scope_from_resolved_intent_default_lookup() {
            PolicyEngine mockPolicy = mock(PolicyEngine.class);
            when(mockPolicy.authorize(eq("order.write")))
                    .thenReturn(PolicyDecision.allow());

            PolicyToolManager hub = new PolicyToolManager(polarisMcpClient, mockPolicy);
            Tool tool = Tool.builder("place_order", Map.of()).build();

            IntentDefinition intentDef = mock(IntentDefinition.class);
            when(intentDef.allowedTools()).thenReturn(List.of("place_order"));
            when(intentDef.requiredScope()).thenReturn("order.write");

            ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
            when(resolvedIntent.intentId()).thenReturn("commerce.order.place");
            when(resolvedIntent.meetsThreshold()).thenReturn(true);
            when(resolvedIntent.filteredTools()).thenReturn(List.of(tool));
            when(resolvedIntent.intentDefinition()).thenReturn(intentDef);

            ToolExecutionContext context = new ToolExecutionContext("sess-43", "user-43", 1, resolvedIntent);
            ToolCall toolCall = new ToolCall("place_order", Map.of());

            ToolPolicyCheckResult checkResult = hub.checkPolicy(toolCall, context);

            assertThat(checkResult.isOk()).isTrue();
            verify(mockPolicy).authorize("order.write");
        }
    }

    // =========================================================================
    // 3. Edge cases — null safety, failures, parallel calls grouping
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {
        @Test
        @DisplayName("Given multiple parallel tool calls, when handled, executes them concurrently in parallel and returns all tool results")
        void executes_remote_tool_calls_concurrently_in_parallel() {
            java.util.concurrent.CountDownLatch latch1 = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch latch2 = new java.util.concurrent.CountDownLatch(1);

            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenAnswer(inv -> {
                latch1.countDown();
                boolean unblocked = latch2.await(2, java.util.concurrent.TimeUnit.SECONDS);
                if (!unblocked) {
                    throw new IllegalStateException("Timeout waiting for search_promotions; calls did not execute concurrently");
                }
                return new CallToolResult(List.of(TextContent.builder("Charger").build()), false, null, Map.of());
            });

            when(polarisMcpClient.callTool(eq("search_promotions"), any())).thenAnswer(inv -> {
                latch2.countDown();
                boolean unblocked = latch1.await(2, java.util.concurrent.TimeUnit.SECONDS);
                if (!unblocked) {
                    throw new IllegalStateException("Timeout waiting for search_available_products; calls did not execute concurrently");
                }
                return new CallToolResult(List.of(TextContent.builder("10% off").build()), false, null, Map.of());
            });

            Tool t1 = Tool.builder("search_available_products", Map.of()).build();
            Tool t2 = Tool.builder("search_promotions", Map.of()).build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, new ResolvedIntent("general.conversation", 0.95, false, List.of(t1, t2))
            );
            List<ToolCall> toolCalls = List.of(
                    new ToolCall("search_available_products", Map.of("query", "charger"), "sig-1"),
                    new ToolCall("search_promotions", Map.of("category", "all"), "sig-2")
            );

            List<ToolResult> results = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(results).hasSize(2);
            assertThat(results.getFirst().toolCall().name()).isEqualTo("search_available_products");
            assertThat(results.get(0).result()).isEqualTo("Charger");
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(1).toolCall().name()).isEqualTo("search_promotions");
            assertThat(results.get(1).result()).isEqualTo("10% off");
            assertThat(results.get(1).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Given active security context, when executing concurrent tool calls, propagates security context to tasks")
        void propagates_security_context_to_concurrent_tasks() {
            org.springframework.security.core.context.SecurityContext testContext =
                    org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            testContext.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("user-test", "pass", "SCOPE_catalog.read"));
            org.springframework.security.core.context.SecurityContextHolder.setContext(testContext);

            try {
                when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenAnswer(inv -> {
                    org.springframework.security.core.Authentication currentAuth =
                            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    if (currentAuth == null || !"user-test".equals(currentAuth.getName())) {
                        throw new IllegalStateException("SecurityContext not propagated to worker thread");
                    }
                    return new CallToolResult(List.of(TextContent.builder("Auth OK").build()), false, null, Map.of());
                });

                Tool t = Tool.builder("search_available_products", Map.of()).build();
                ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, new ResolvedIntent("general.conversation", 0.95, false, List.of(t))
                );
                List<ToolResult> results = mcpHub.handleToolCalls(
                        List.of(new ToolCall("search_available_products", Map.of("query", "charger"), "sig-1")),
                        context
                );

                assertThat(results).hasSize(1);
                assertThat(results.getFirst().result()).isEqualTo("Auth OK");
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName("Given null or empty tool calls, when handled, then returns empty results")
        void returns_empty_result_for_null_or_empty_tool_calls() {
            assertThat(mcpHub.handleToolCalls(null, null)).isEmpty();
            assertThat(mcpHub.handleToolCalls(List.of(), null)).isEmpty();
        }

        @Test
        @DisplayName("Given null tool call, when checkPolicy evaluated, returns rejected result safely")
        void checkPolicy_handles_null_tool_call() {
            ToolPolicyCheckResult result = mcpHub.checkPolicy(null, null);

            assertThat(result.isOk()).isFalse();
            assertThat(result.isRejected()).isTrue();
            assert result.rejection() != null;
            assertThat(result.rejection().isError()).isTrue();
        }

        @Test
        @DisplayName("Given mixed tool calls with policy violation and valid tool, when handled, marks violating tool done and executes valid tool concurrently")
        void handles_mixed_tool_calls_marking_forbidden_done_and_executing_valid_concurrently() {
            CallToolResult validResult = new CallToolResult(List.of(TextContent.builder("Charger").build()), false, null, Map.of());
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(validResult);

            Tool validTool = Tool.builder("search_available_products", Map.of()).build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, new ResolvedIntent("catalog.product.search", 0.95, true, List.of(validTool))
            );
            List<ToolCall> toolCalls = List.of(
                    new ToolCall("unauthorized_action", Map.of()),
                    new ToolCall("search_available_products", Map.of("query", "charger"))
            );

            List<ToolResult> results = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).isError()).isTrue();
            assertThat(results.get(0).toolCall().name()).isEqualTo("unauthorized_action");
            assertThat(results.get(1).isSuccess()).isTrue();
            assertThat(results.get(1).result()).isEqualTo("Charger");
            verify(polarisMcpClient, times(1)).callTool(eq("search_available_products"), any());
            verify(polarisMcpClient, never()).callTool(eq("unauthorized_action"), any());
        }

        @Test
        @DisplayName("Given PolicyToolManager with managed executor, when destroyed, then shuts down executor")
        void shuts_down_managed_executor_on_destroy() {
            PolicyToolManager manager = new PolicyToolManager(polarisMcpClient);
            manager.destroy();
            // calling destroy again is safe and idempotent
            manager.destroy();
        }

        @Test
        @DisplayName("Given PolicyToolManager with custom unmanaged executor, when destroyed, does not shut down custom executor")
        void does_not_shut_down_custom_executor_on_destroy() {
            ExecutorService customExecutor = Executors.newSingleThreadExecutor();
            try {
                PolicyToolManager manager = new PolicyToolManager(polarisMcpClient, null, customExecutor);
                manager.destroy();
                assertThat(customExecutor.isShutdown()).isFalse();
            } finally {
                customExecutor.shutdown();
            }
        }

        @Test
        @DisplayName("Given PolicyToolManager with custom ObjectProviders, initializes with provided beans")
        @SuppressWarnings("unchecked")
        void initializes_with_provided_beans_from_object_providers() {
            ObjectProvider<PolicyEngine> policyProvider = mock(ObjectProvider.class);
            ObjectProvider<Executor> executorProvider = mock(ObjectProvider.class);
            PolicyEngine customPolicy = mock(PolicyEngine.class);
            ExecutorService customExecutor = Executors.newSingleThreadExecutor();

            when(policyProvider.getIfAvailable()).thenReturn(customPolicy);
            when(executorProvider.getIfAvailable()).thenReturn(customExecutor);

            PolicyToolManager manager = new PolicyToolManager(polarisMcpClient, policyProvider, executorProvider);

            try {
                manager.destroy();
                assertThat(customExecutor.isShutdown()).isFalse();
            } finally {
                customExecutor.shutdown();
            }
        }

        @Test
        @DisplayName("Given null ObjectProviders in constructor, falls back to defaults safely")
        void falls_back_to_defaults_when_providers_null() {
            ObjectProvider<PolicyEngine> nullPolicyProvider = null;
            ObjectProvider<Executor> nullExecutorProvider = null;
            PolicyToolManager manager = new PolicyToolManager(polarisMcpClient, nullPolicyProvider, nullExecutorProvider);
            manager.destroy();
        }
    }
}
