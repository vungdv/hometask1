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
}
