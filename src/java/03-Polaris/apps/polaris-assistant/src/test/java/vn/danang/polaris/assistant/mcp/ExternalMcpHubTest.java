package vn.danang.polaris.assistant.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.intent.IntentToolRegistry;
import vn.danang.polaris.assistant.intent.PolicyDecision;
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.model.ToolCall;
import vn.danang.polaris.assistant.observability.AgentDecisionRecorder;

@ExtendWith(MockitoExtension.class)
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

    @Test
    void testDiscoverAllTools_delegatesToPolarisMcpClient() {
        Tool polarisTool = Tool.builder("search_available_products").description("Search available products in catalog").build();
        when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(polarisTool));

        List<Tool> allTools = mcpHub.discoverAllTools();

        assertEquals(1, allTools.size());
        assertEquals("search_available_products", allTools.get(0).name());
        verify(polarisMcpClient, times(1)).listAvailableTools();
    }

    @Test
    void testExecuteTool_withoutTracer_routesToPolarisMcpClient() {
        CallToolResult expectedResult = new CallToolResult(
                List.of(new TextContent("Found 3 products")),
                false,
                null,
                Map.of()
        );
        when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(expectedResult);

        CallToolResult actual = mcpHub.executeTool("search_available_products", Map.of("query", "charger"));

        assertFalse(actual.isError());
        assertEquals("Found 3 products", ((TextContent) actual.content().get(0)).text());
        verify(polarisMcpClient, times(1)).callTool(eq("search_available_products"), any());
    }

    @Test
    void testExecuteTool_withTracer_createsSpanAndTagsForPolarisTool() {
        mockTracerSetup();
        ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

        CallToolResult expectedResult = new CallToolResult(
                List.of(new TextContent("Found 3 products")),
                false,
                null,
                Map.of()
        );
        when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(expectedResult);

        CallToolResult actual = tracedHub.executeTool("search_available_products", Map.of("query", "charger"));

        assertFalse(actual.isError());
        verify(tracer, times(1)).nextSpan();
        verify(span, times(1)).name("mcp.tool_call search_available_products");
        verify(span, times(1)).tag("gen_ai.tool.name", "search_available_products");
        verify(span, never()).tag(eq("mcp.tool.name"), anyString());
        verify(span, times(1)).tag("mcp.provider", "polaris-core");
        verify(span, times(1)).start();
        verify(tracer, times(1)).withSpan(span);
        verify(spanInScope, times(1)).close();
        verify(span, times(1)).end();
    }

    @Test
    void testExecuteTool_withTracer_tagsErrorWhenToolReturnsErrorResult() {
        mockTracerSetup();
        ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

        CallToolResult errorResult = new CallToolResult(
                List.of(new TextContent("Failed to search")),
                true,
                null,
                Map.of()
        );
        when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(errorResult);

        CallToolResult actual = tracedHub.executeTool("search_available_products", Map.of());

        assertTrue(actual.isError());
        verify(span, times(1)).tag("error", "true");
        verify(spanInScope, times(1)).close();
        verify(span, times(1)).end();
    }

    @Test
    void testExecuteTool_withTracer_recordsExceptionAndEndsSpanOnFailure() {
        mockTracerSetup();
        ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

        RuntimeException exception = new RuntimeException("Polaris Core connection timed out");
        when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenThrow(exception);

        assertThrows(RuntimeException.class, () ->
                tracedHub.executeTool("search_available_products", Map.of())
        );

        verify(span, times(1)).error(exception);
        verify(span, times(1)).tag("error", "true");
        verify(spanInScope, times(1)).close();
        verify(span, times(1)).end();
    }

    @Test
    void testExecuteTool_withNullToolName_handlesGracefully() {
        mockTracerSetup();
        ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

        tracedHub.executeTool(null, Map.of());

        verify(span, times(1)).name("mcp.tool_call unknown");
        verify(span, times(1)).tag("mcp.provider", "polaris-core");
        verify(span, times(1)).end();
    }

    @Test
    void testConstructor_withObjectProvider_extractsTracer() {
        mockTracerSetup();
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);

        ExternalMcpHub hub = new ExternalMcpHub(polarisMcpClient, provider);
        hub.executeTool("test_tool", Map.of());

        verify(tracer, times(1)).nextSpan();
    }

    @Test
    void testDiscoverAllTools_withTracer_createsSpanAndTags() {
        mockTracerSetup();
        ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

        Tool polarisTool = Tool.builder("search_available_products").description("Search").build();
        when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(polarisTool));

        List<Tool> allTools = tracedHub.discoverAllTools();

        assertEquals(1, allTools.size());
        verify(tracer, times(1)).nextSpan();
        verify(span, times(1)).name("mcp.list_tools");
        verify(span, times(1)).tag("mcp.provider", "polaris-core");
        verify(span, times(1)).tag("mcp.operation", "tools/list");
        verify(span, times(1)).tag("mcp.tools.count", "1");
        verify(span, times(1)).start();
        verify(tracer, times(1)).withSpan(span);
        verify(spanInScope, times(1)).close();
        verify(span, times(1)).end();
    }

    @Test
    void testDiscoverAllTools_withTracer_recordsExceptionOnFailure() {
        mockTracerSetup();
        ExternalMcpHub tracedHub = new ExternalMcpHub(polarisMcpClient, tracer);

        RuntimeException ex = new RuntimeException("MCP server connection refused");
        when(polarisMcpClient.listAvailableTools()).thenThrow(ex);

        assertThrows(RuntimeException.class, tracedHub::discoverAllTools);

        verify(span, times(1)).error(ex);
        verify(span, times(1)).tag("error", "true");
        verify(spanInScope, times(1)).close();
        verify(span, times(1)).end();
    }

    @Test
    void testHandleToolCalls_validTool_executesToolAndReturnsMessages() {
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

        assertFalse(result.policyDenied());
        assertNull(result.denialReason());
        assertEquals(2, result.messages().size());

        AssistantMessage modelMsg = result.messages().get(0);
        assertEquals(MessageRole.ASSISTANT, modelMsg.getRole());
        assertEquals("search_available_products", modelMsg.getToolCallId());
        assertEquals("sig-123", modelMsg.getThoughtSignature());

        AssistantMessage toolMsg = result.messages().get(1);
        assertEquals(MessageRole.TOOL, toolMsg.getRole());
        assertEquals("search_available_products", toolMsg.getToolCallId());
        assertEquals("Product found: Charger", toolMsg.getContent());

        verify(polarisMcpClient, times(1)).callTool(eq("search_available_products"), any());
    }

    @Test
    void testHandleToolCalls_unpermittedTool_returnsCorrectiveMessageWithoutExecuting() {
        Tool allowedTool = Tool.builder("search_available_products").build();
        ToolExecutionContext context = new ToolExecutionContext(
                "sess-1", "user-1", 1, "catalog.product.search", 0.95, true, List.of(allowedTool), null
        );
        List<ToolCall> toolCalls = List.of(new ToolCall("cancel_order", Map.of("order_id", "123")));

        ToolExecutionResult result = mcpHub.handleToolCalls(toolCalls, context);

        assertFalse(result.policyDenied());
        assertEquals(2, result.messages().size());
        assertEquals(MessageRole.TOOL, result.messages().get(1).getRole());
        assertTrue(result.messages().get(1).getContent().contains("not permitted for intent 'catalog.product.search'"));

        verify(polarisMcpClient, never()).callTool(anyString(), any());
    }

    @Test
    void testHandleToolCalls_policyDenied_stopsExecutionAndReturnsDenial() {
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

        assertTrue(result.policyDenied());
        assertEquals("Missing scope order.write", result.denialReason());
        verify(polarisMcpClient, never()).callTool(anyString(), any());
    }

    @Test
    void testHandleToolCalls_parallelToolCalls_groupsModelTurnsBeforeToolTurns() {
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

        assertEquals(4, result.messages().size());
        assertEquals(MessageRole.ASSISTANT, result.messages().get(0).getRole());
        assertEquals("search_available_products", result.messages().get(0).getToolCallId());
        assertEquals(MessageRole.ASSISTANT, result.messages().get(1).getRole());
        assertEquals("search_promotions", result.messages().get(1).getToolCallId());
        assertEquals(MessageRole.TOOL, result.messages().get(2).getRole());
        assertEquals("search_available_products", result.messages().get(2).getToolCallId());
        assertEquals(MessageRole.TOOL, result.messages().get(3).getRole());
        assertEquals("search_promotions", result.messages().get(3).getToolCallId());
    }

    @Test
    void testHandleToolCalls_nullOrEmpty_returnsEmptyResult() {
        assertTrue(mcpHub.handleToolCalls(null, null).messages().isEmpty());
        assertTrue(mcpHub.handleToolCalls(List.of(), null).messages().isEmpty());
    }
}
