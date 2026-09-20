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
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.intent.IntentToolRegistry;
import vn.danang.polaris.assistant.intent.PolicyDecision;
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.model.ToolCall;

@ExtendWith(MockitoExtension.class)
@DisplayName("Feature: ExternalMcpHub Model Context Protocol Gateway & Routing")
class ExternalMcpHubTest {

    @Mock
    private PolarisMcpClient polarisMcpClient;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private Tracer.SpanInScope spanInScope;

    private ExternalMcpHub mcpHub;

    @BeforeEach
    void setUp() {
        mcpHub = new ExternalMcpHub(polarisMcpClient);
    }

    private void mockTracerSetup() {
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.tag(anyString(), anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(spanInScope);
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
        @DisplayName("Given valid tool call without tracer, when executed, then routes to client and returns content")
        void executes_tool_without_tracer_and_returns_content() {
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
        @DisplayName("Given tracer is present, when executing tool, then creates span with GenAI and provider tags")
        void creates_span_with_tags_when_executing_tool_with_tracer() {
            mockTracerSetup();
            ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

            CallToolResult expected = new CallToolResult(
                    List.of(new TextContent("Found 3 products")),
                    false,
                    null,
                    Map.of()
            );
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(expected);

            tracedHub.executeTool("search_available_products", Map.of("query", "charger"));

            verify(tracer, times(1)).nextSpan();
            verify(span, times(1)).name("mcp.tool_call search_available_products");
            verify(span, times(1)).tag("gen_ai.tool.name", "search_available_products");
            verify(span, never()).tag(eq("mcp.tool.name"), anyString());
            verify(span, times(1)).tag("mcp.provider", "polaris-core");
            verify(span, times(1)).start();
            verify(spanInScope, times(1)).close();
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given tracer is present, when discovering tools, then creates span with tools count tag")
        void creates_span_when_discovering_tools_with_tracer() {
            mockTracerSetup();
            ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

            Tool tool = Tool.builder("search_available_products").description("Search").build();
            when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(tool));

            List<Tool> allTools = tracedHub.discoverAllTools();

            assertThat(allTools).hasSize(1);
            verify(tracer, times(1)).nextSpan();
            verify(span, times(1)).name("mcp.list_tools");
            verify(span, times(1)).tag("mcp.provider", "polaris-core");
            verify(span, times(1)).tag("mcp.tools.count", "1");
            verify(spanInScope, times(1)).close();
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given permitted tool call, when handled, then executes tool and generates paired assistant and tool messages")
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
                    "sess-1", "user-1", 1, "catalog.product.search", 0.95, true, List.of(tool), null
            );
            List<ToolCall> toolCalls = List.of(new ToolCall("search_available_products", Map.of("query", "charger"), "sig-123"));

            ToolExecutionResult result = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(result.policyDenied()).isFalse();
            assertThat(result.messages()).hasSize(2);

            AssistantMessage modelMsg = result.messages().get(0);
            assertThat(modelMsg.getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(modelMsg.getToolCallId()).isEqualTo("search_available_products");
            assertThat(modelMsg.getThoughtSignature()).isEqualTo("sig-123");

            AssistantMessage toolMsg = result.messages().get(1);
            assertThat(toolMsg.getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(toolMsg.getToolCallId()).isEqualTo("search_available_products");
            assertThat(toolMsg.getContent()).isEqualTo("Product found: Charger");
        }
    }

    // =========================================================================
    // 2. Invalid input & policy enforcement
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & policy enforcement")
    class InvalidInput {

        @Test
        @DisplayName("Given tool forbidden for current intent, when handled, then returns corrective tool message without invoking client")
        void rejects_forbidden_tool_with_corrective_message_without_client_call() {
            Tool allowedTool = Tool.builder("search_available_products").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "catalog.product.search", 0.95, true, List.of(allowedTool), null
            );
            List<ToolCall> toolCalls = List.of(new ToolCall("cancel_order", Map.of("order_id", "123")));

            ToolExecutionResult result = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(result.policyDenied()).isFalse();
            assertThat(result.messages()).hasSize(2);
            assertThat(result.messages().get(1).getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(result.messages().get(1).getContent()).contains("not permitted for intent 'catalog.product.search'");
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }

        @Test
        @DisplayName("Given policy engine denies scope, when handled, then stops execution and returns denial reason")
        void stops_execution_and_returns_denial_when_policy_denies_scope() {
            PolicyEngine mockPolicy = mock(PolicyEngine.class);
            when(mockPolicy.authorize(anyString(), eq("order.write")))
                    .thenReturn(PolicyDecision.deny("Missing scope order.write"));

            ExternalMcpHub hubWithPolicy = new ExternalMcpHub(
                    polarisMcpClient, null, new ObjectMapper(), null, new IntentToolRegistry(), mockPolicy
            );

            Tool tool = Tool.builder("place_order").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "commerce.order.place", 0.95, true, List.of(tool), null
            );
            List<ToolCall> toolCalls = List.of(new ToolCall("place_order", Map.of("sku", "PROD-1")));

            ToolExecutionResult result = hubWithPolicy.handleToolCalls(toolCalls, context);

            assertThat(result.policyDenied()).isTrue();
            assertThat(result.denialReason()).isEqualTo("Missing scope order.write");
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }
    }

    // =========================================================================
    // 3. Edge cases — null safety, failures, parallel calls grouping
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given null tool name, when executed with tracer, then handles gracefully with 'unknown' span name")
        void handles_null_tool_name_gracefully() {
            mockTracerSetup();
            ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

            tracedHub.executeTool(null, Map.of());

            verify(span, times(1)).name("mcp.tool_call unknown");
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given tool result with isError=true, when executed, then tags error on span")
        void tags_error_when_tool_returns_error_result() {
            mockTracerSetup();
            ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

            CallToolResult errorResult = new CallToolResult(
                    List.of(new TextContent("Failed")),
                    true,
                    null,
                    Map.of()
            );
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(errorResult);

            CallToolResult actual = tracedHub.executeTool("search_available_products", Map.of());

            assertThat(actual.isError()).isTrue();
            verify(span, times(1)).tag("error", "true");
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given client throws exception, when executing tool, then records exception and ends span")
        void records_exception_and_ends_span_on_failure() {
            mockTracerSetup();
            ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

            RuntimeException exception = new RuntimeException("Core timeout");
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenThrow(exception);

            assertThatThrownBy(() -> tracedHub.executeTool("search_available_products", Map.of()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Core timeout");

            verify(span, times(1)).error(exception);
            verify(span, times(1)).tag("error", "true");
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given multiple parallel tool calls, when handled, then groups all ASSISTANT turns before TOOL turns")
        void groups_model_turns_before_tool_turns_in_parallel_calls() {
            CallToolResult res1 = new CallToolResult(List.of(new TextContent("Charger")), false, null, Map.of());
            CallToolResult res2 = new CallToolResult(List.of(new TextContent("10% off")), false, null, Map.of());
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(res1);
            when(polarisMcpClient.callTool(eq("search_promotions"), any())).thenReturn(res2);

            Tool t1 = Tool.builder("search_available_products").build();
            Tool t2 = Tool.builder("search_promotions").build();
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, "general.conversation", 0.95, false, List.of(t1, t2), null
            );
            List<ToolCall> toolCalls = List.of(
                    new ToolCall("search_available_products", Map.of("query", "charger"), "sig-1"),
                    new ToolCall("search_promotions", Map.of("category", "all"), "sig-2")
            );

            ToolExecutionResult result = mcpHub.handleToolCalls(toolCalls, context);

            assertThat(result.messages()).hasSize(4);
            assertThat(result.messages().get(0).getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(result.messages().get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(result.messages().get(2).getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(result.messages().get(3).getRole()).isEqualTo(MessageRole.TOOL);
        }

        @Test
        @DisplayName("Given null or empty tool calls, when handled, then returns empty messages result")
        void returns_empty_result_for_null_or_empty_tool_calls() {
            assertThat(mcpHub.handleToolCalls(null, null).messages()).isEmpty();
            assertThat(mcpHub.handleToolCalls(List.of(), null).messages()).isEmpty();
        }

        @Test
        @DisplayName("Given ObjectProvider constructor, when instantiated, then extracts Tracer bean")
        @SuppressWarnings("unchecked")
        void extracts_tracer_from_object_provider() {
            mockTracerSetup();
            ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(tracer);

            ExternalMcpHub hub = new ExternalMcpHub(polarisMcpClient, provider);
            hub.executeTool("test_tool", Map.of());

            verify(tracer, times(1)).nextSpan();
        }
    }
}
