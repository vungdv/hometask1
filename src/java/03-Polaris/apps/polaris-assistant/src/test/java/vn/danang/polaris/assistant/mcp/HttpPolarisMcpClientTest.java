package vn.danang.polaris.assistant.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

class HttpPolarisMcpClientTest {

    private HttpPolarisMcpClient client;

    @BeforeEach
    void setUp() {
        PolarisMcpProperties props = new PolarisMcpProperties();
        // Point to an unreachable port to test offline handling
        props.getCore().setUrl("http://127.0.0.1:59999/mcp/sse");
        props.getCore().setTimeoutSeconds(1);

        client = new HttpPolarisMcpClient(props, new ObjectMapper());
    }

    @Test
    void testListAvailableTools_whenOffline_returnsEmptyList() {
        List<Tool> tools = client.listAvailableTools();
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testCallTool_whenOffline_returnsErrorResult() {
        CallToolResult result = client.callTool("search_available_products", Map.of("query", "charger"));
        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(((TextContent) result.content().get(0)).text().contains("unreachable"));
    }
}
