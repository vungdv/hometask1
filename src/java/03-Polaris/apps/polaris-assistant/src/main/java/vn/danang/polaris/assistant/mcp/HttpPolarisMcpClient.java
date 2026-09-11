package vn.danang.polaris.assistant.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

@Component
public class HttpPolarisMcpClient implements PolarisMcpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPolarisMcpClient.class);

    private final PolarisMcpProperties properties;
    private final ObjectMapper objectMapper;
    private McpSyncClient mcpSyncClient;

    public HttpPolarisMcpClient(PolarisMcpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public synchronized McpSyncClient getOrInitClient() {
        if (mcpSyncClient != null && mcpSyncClient.isInitialized()) {
            return mcpSyncClient;
        }

        String fullUrl = properties.getCore().getUrl();
        try {
            URI uri = URI.create(fullUrl);
            String baseUri = uri.getScheme() + "://" + uri.getAuthority();
            String ssePath = uri.getPath() != null && !uri.getPath().isBlank() ? uri.getPath() : "/mcp/sse";

            JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(baseUri)
                    .sseEndpoint(ssePath)
                    .jsonMapper(jsonMapper)
                    .connectTimeout(Duration.ofSeconds(properties.getCore().getTimeoutSeconds()))
                    .build();

            McpSyncClient client = McpClient.sync(transport)
                    .clientInfo(new McpSchema.Implementation("polaris-assistant", "1.0.0"))
                    .capabilities(McpSchema.ClientCapabilities.builder().build())
                    .requestTimeout(Duration.ofSeconds(properties.getCore().getTimeoutSeconds()))
                    .build();

            client.initialize();
            this.mcpSyncClient = client;
            log.info("Successfully initialized MCP Client connected to Polaris Core at {}", fullUrl);
            return this.mcpSyncClient;
        } catch (Exception e) {
            log.warn("Unable to connect to Polaris Core MCP server at {}: {}", fullUrl, e.getMessage());
            return null;
        }
    }

    @Override
    public List<Tool> listAvailableTools() {
        McpSyncClient client = getOrInitClient();
        if (client == null) {
            log.warn("Polaris Core MCP client is not connected. Returning empty tools list.");
            return Collections.emptyList();
        }
        try {
            return client.listTools().tools();
        } catch (Exception e) {
            log.error("Failed to list tools from Polaris Core MCP server: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public CallToolResult callTool(String toolName, Map<String, Object> arguments) {
        McpSyncClient client = getOrInitClient();
        if (client == null) {
            log.warn("Polaris Core MCP client is not connected. Cannot call tool: {}", toolName);
            return new CallToolResult(
                    List.of(new TextContent("Error: Polaris Core MCP server is currently unreachable.")),
                    true,
                    null,
                    Map.of()
            );
        }
        try {
            CallToolRequest request = new CallToolRequest(toolName, arguments != null ? arguments : Map.of());
            return client.callTool(request);
        } catch (Exception e) {
            log.error("Error executing MCP tool '{}': {}", toolName, e.getMessage());
            return new CallToolResult(
                    List.of(new TextContent("Error executing tool " + toolName + ": " + e.getMessage())),
                    true,
                    null,
                    Map.of()
            );
        }
    }
}
