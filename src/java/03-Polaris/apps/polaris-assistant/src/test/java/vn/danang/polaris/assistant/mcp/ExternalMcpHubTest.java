package vn.danang.polaris.assistant.mcp;

import java.util.List;
import java.util.Map;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.intent.IntentToolRegistry;
import vn.danang.polaris.assistant.intent.PolicyDecision;
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.model.ToolCall;

@ExtendWith(MockitoExtension.class)
class ExternalMcpHubTest {

    @Mock
    private PolarisMcpClient polarisMcpClient;

    private ToolManager mcpHub;

    @BeforeEach
    void setUp() {
        mcpHub = new ToolManager(polarisMcpClient);
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
            Tool tool = Tool.builder("search_available_products")
                    .description("Search products")
                    .build();
            when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(tool));

            List<Tool> tools = mcpHub.discoverAllTools();

            assertThat(tools)
                    .extracting(Tool::name)
                    .containsExactly("search_available_products");
            verify(polarisMcpClient, times(1)).listAvailableTools();
        }

        @Test
        @DisplayName("Given valid tool call, when executed, then routes to client and returns content")
        void executes_tool_and_returns_content() {
            CallToolResult expected = new CallToolResult(
                    List.of(new TextContent("Found 3 products")),
                    false,
                    null,
                    Map.of()
            );
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(expected);

            CallToolResult actual = mcpHub.executeTool("search_available_products", Map.of("query", "charger"));

            assertThat(actual.isError()).isFalse();
            assertThat(((TextContent) actual.content().get(0)).text()).isEqualTo("Found 3 products");
            verify(polarisMcpClient, times(1)).callTool(eq("search_available_products"), any());
        }

        @Test
        @DisplayName("Given permitted tool call, when handled, then executes tool and returns success ToolResult")
        void handles_permitted_tool_calls_and_returns_turn_messages() {
            CallToolResult toolResult = new CallToolResult(
                    List.of(new TextContent("Product found: Charger")),
                    false,
                    null,
                    Map.of()
            );
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(toolResult);

            Tool tool = Tool.builder("search_available_products").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "catalog.product.search", 0.95, true, List.of(tool)
            );
            List<ToolCall> toolCalls = List.of(new ToolCall("search_available_products", Map.of("query", "charger"), "sig-123"));

            List<ToolResult> results = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(results).hasSize(1);
            ToolResult result = results.get(0);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.SUCCESS);
            assertThat(result.toolCall()).isEqualTo(toolCalls.get(0));
            assertThat(result.result()).isEqualTo("Product found: Charger");
            assertThat(result.errorDescription()).isNull();
        }

        @Test
        @DisplayName("Given valid authorized tool call, when checkPolicy evaluated, returns ok ToolPolicyCheckResult")
        void checkPolicy_returns_ok_for_valid_authorized_tool() {
            Tool tool = Tool.builder("search_available_products").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "catalog.product.search", 0.95, true, List.of(tool)
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
            Tool allowedTool = Tool.builder("search_available_products").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "catalog.product.search", 0.95, true, List.of(allowedTool)
            );
            List<ToolCall> toolCalls = List.of(new ToolCall("cancel_order", Map.of("order_id", "123")));

            List<ToolResult> results = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(results).hasSize(1);
            ToolResult result = results.get(0);
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
            when(mockPolicy.authorize(anyString(), eq("order.write")))
                    .thenReturn(PolicyDecision.deny("Missing scope order.write"));

            ToolManager hubWithPolicy = new ToolManager(
                    polarisMcpClient, new ObjectMapper(), new IntentToolRegistry(), mockPolicy
            );

            Tool tool = Tool.builder("place_order").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "commerce.order.place", 0.95, true, List.of(tool)
            );
            List<ToolCall> toolCalls = List.of(new ToolCall("place_order", Map.of("sku", "PROD-1")));

            List<ToolResult> results = hubWithPolicy.handleToolCalls(toolCalls, context);

            assertThat(results).hasSize(1);
            ToolResult result = results.get(0);
            assertThat(result.isDenied()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.DENIED);
            assertThat(result.result()).isEqualTo("Missing scope order.write");
            assertThat(result.errorDescription()).isEqualTo("Policy authorization denied execution of tool 'place_order' for user 'user-1': Missing scope order.write");
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }

        @Test
        @DisplayName("Given tool forbidden for intent, when checkPolicy evaluated, returns rejected ToolPolicyCheckResult")
        void checkPolicy_returns_rejected_for_forbidden_tool() {
            Tool allowedTool = Tool.builder("search_available_products").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "catalog.product.search", 0.95, true, List.of(allowedTool)
            );
            ToolCall toolCall = new ToolCall("cancel_order", Map.of());

            ToolPolicyCheckResult checkResult = mcpHub.checkPolicy(toolCall, context);

            assertThat(checkResult.isOk()).isFalse();
            assertThat(checkResult.isRejected()).isTrue();
            assertThat(checkResult.rejection().isError()).isTrue();
        }

        @Test
        @DisplayName("Given policy engine denies scope, when checkPolicy evaluated, returns rejected ToolPolicyCheckResult with denied status")
        void checkPolicy_returns_rejected_when_policy_engine_denies() {
            PolicyEngine mockPolicy = mock(PolicyEngine.class);
            when(mockPolicy.authorize(eq("user-1"), eq("order.write")))
                    .thenReturn(PolicyDecision.deny("Missing scope order.write"));

            ToolManager hubWithPolicy = new ToolManager(
                    polarisMcpClient, new ObjectMapper(), new IntentToolRegistry(), mockPolicy
            );

            Tool tool = Tool.builder("place_order").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "commerce.order.place", 0.95, true, List.of(tool)
            );
            ToolCall toolCall = new ToolCall("place_order", Map.of("sku", "PROD-1"));

            ToolPolicyCheckResult checkResult = hubWithPolicy.checkPolicy(toolCall, context);

            assertThat(checkResult.isOk()).isFalse();
            assertThat(checkResult.isRejected()).isTrue();
            assertThat(checkResult.rejection().isDenied()).isTrue();
            assertThat(checkResult.rejection().status()).isEqualTo(ToolResult.Status.DENIED);
            assertThat(checkResult.rejection().result()).isEqualTo("Missing scope order.write");
            assertThat(checkResult.rejection().errorDescription())
                    .isEqualTo("Policy authorization denied execution of tool 'place_order' for user 'user-1': Missing scope order.write");
        }
    }

    // =========================================================================
    // 3. Edge cases — null safety, failures, parallel calls grouping
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given null tool name, when executed, then handles gracefully")
        void handles_null_tool_name_gracefully() {
            mcpHub.executeTool(null, Map.of());
            verify(polarisMcpClient, times(1)).callTool(eq(null), any());
        }

        @Test
        @DisplayName("Given tool result with isError=true, when executed, then returns error result")
        void returns_error_when_tool_returns_error_result() {
            CallToolResult errorResult = new CallToolResult(
                    List.of(new TextContent("Failed")),
                    true,
                    null,
                    Map.of()
            );
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(errorResult);

            CallToolResult actual = mcpHub.executeTool("search_available_products", Map.of());

            assertThat(actual.isError()).isTrue();
        }

        @Test
        @DisplayName("Given client throws exception, when executing tool, then propagates exception")
        void propagates_exception_on_failure() {
            RuntimeException exception = new RuntimeException("Core timeout");
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenThrow(exception);

            assertThatThrownBy(() -> mcpHub.executeTool("search_available_products", Map.of()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Core timeout");
        }

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
                return new CallToolResult(List.of(new TextContent("Charger")), false, null, Map.of());
            });

            when(polarisMcpClient.callTool(eq("search_promotions"), any())).thenAnswer(inv -> {
                latch2.countDown();
                boolean unblocked = latch1.await(2, java.util.concurrent.TimeUnit.SECONDS);
                if (!unblocked) {
                    throw new IllegalStateException("Timeout waiting for search_available_products; calls did not execute concurrently");
                }
                return new CallToolResult(List.of(new TextContent("10% off")), false, null, Map.of());
            });

            Tool t1 = Tool.builder("search_available_products").build();
            Tool t2 = Tool.builder("search_promotions").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "general.conversation", 0.95, false, List.of(t1, t2)
            );
            List<ToolCall> toolCalls = List.of(
                    new ToolCall("search_available_products", Map.of("query", "charger"), "sig-1"),
                    new ToolCall("search_promotions", Map.of("category", "all"), "sig-2")
            );

            List<ToolResult> results = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).toolCall().name()).isEqualTo("search_available_products");
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
                    return new CallToolResult(List.of(new TextContent("Auth OK")), false, null, Map.of());
                });

                Tool t = Tool.builder("search_available_products").build();
                ToolExecutionContext context = new ToolExecutionContext(
                        "sess-1", "user-1", 1, "general.conversation", 0.95, false, List.of(t)
                );
                List<ToolResult> results = mcpHub.handleToolCalls(
                        List.of(new ToolCall("search_available_products", Map.of("query", "charger"), "sig-1")),
                        context
                );

                assertThat(results).hasSize(1);
                assertThat(results.get(0).result()).isEqualTo("Auth OK");
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
            assertThat(result.rejection().isError()).isTrue();
        }

        @Test
        @DisplayName("Given mixed tool calls with policy violation and valid tool, when handled, marks violating tool done and executes valid tool concurrently")
        void handles_mixed_tool_calls_marking_forbidden_done_and_executing_valid_concurrently() {
            CallToolResult validResult = new CallToolResult(List.of(new TextContent("Charger")), false, null, Map.of());
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(validResult);

            Tool validTool = Tool.builder("search_available_products").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "catalog.product.search", 0.95, true, List.of(validTool)
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
    }
}
