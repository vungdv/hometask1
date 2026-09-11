package vn.danang.polaris.assistant.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

@ExtendWith(MockitoExtension.class)
class ExternalMcpHubTest {

    @Mock
    private PolarisMcpClient polarisMcpClient;

    @Mock
    private McpSyncClient externalClient;

    private ExternalMcpHub mcpHub;

    @BeforeEach
    void setUp() {
        mcpHub = new ExternalMcpHub(polarisMcpClient);
    }

    @Test
    void testDiscoverAllTools_combinesPolarisAndExternalTools() {
        Tool polarisTool = Tool.builder("search_available_products").description("Search available products in catalog").build();
        Tool weatherTool = Tool.builder("get_weather_forecast").description("Get weather forecast for location").build();

        when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(polarisTool));
        when(externalClient.isInitialized()).thenReturn(true);
        when(externalClient.listTools()).thenReturn(new ListToolsResult(List.of(weatherTool), null, Map.of()));

        mcpHub.registerExternalClient("weather-service", externalClient);

        List<Tool> allTools = mcpHub.discoverAllTools();

        assertEquals(2, allTools.size());
        assertTrue(allTools.stream().anyMatch(t -> t.name().equals("search_available_products")));
        assertTrue(allTools.stream().anyMatch(t -> t.name().equals("get_weather_forecast")));
    }

    @Test
    void testExecuteTool_routesToPolarisMcpClient() {
        Tool polarisTool = Tool.builder("search_available_products").description("Search catalog").build();
        when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(polarisTool));

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
        verifyNoInteractions(externalClient);
    }

    @Test
    void testExecuteTool_routesToExternalClientWhenToolIsExternal() {
        when(polarisMcpClient.listAvailableTools()).thenReturn(List.of());

        Tool weatherTool = Tool.builder("get_weather_forecast").description("Get weather").build();
        when(externalClient.isInitialized()).thenReturn(true);
        when(externalClient.listTools()).thenReturn(new ListToolsResult(List.of(weatherTool), null, Map.of()));

        CallToolResult expectedResult = new CallToolResult(
                List.of(new TextContent("Sunny 25C")),
                false,
                null,
                Map.of()
        );
        when(externalClient.callTool(any(CallToolRequest.class))).thenReturn(expectedResult);

        mcpHub.registerExternalClient("weather-service", externalClient);

        CallToolResult actual = mcpHub.executeTool("get_weather_forecast", Map.of("city", "Da Nang"));

        assertFalse(actual.isError());
        assertEquals("Sunny 25C", ((TextContent) actual.content().get(0)).text());
        verify(externalClient, times(1)).callTool(any(CallToolRequest.class));
    }

    @Test
    void testExecuteTool_returnsErrorWhenToolNotFound() {
        when(polarisMcpClient.listAvailableTools()).thenReturn(List.of());

        CallToolResult result = mcpHub.executeTool("unknown_tool", Map.of());

        assertTrue(result.isError());
        assertTrue(((TextContent) result.content().get(0)).text().contains("not supported by any registered MCP server"));
    }
}
