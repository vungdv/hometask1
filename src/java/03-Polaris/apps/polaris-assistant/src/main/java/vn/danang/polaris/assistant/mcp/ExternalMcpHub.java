package vn.danang.polaris.assistant.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Hub and dynamic tool registry for aggregating multiple Model Context Protocol (MCP) clients.
 * Allows the Polaris Assistant application to consume:
 * 1. Polaris Product Catalog & Order operations via PolarisMcpClient
 * 2. External third-party tools & MCP servers (e.g. Search, Weather, Logistics, Knowledge Base)
 */
@Component
public class ExternalMcpHub {

    private static final Logger log = LoggerFactory.getLogger(ExternalMcpHub.class);

    private final PolarisMcpClient polarisMcpClient;
    private final Map<String, McpSyncClient> externalClients = new ConcurrentHashMap<>();

    public ExternalMcpHub(PolarisMcpClient polarisMcpClient) {
        this.polarisMcpClient = polarisMcpClient;
    }

    /**
     * Registers an external MCP client connection under a unique provider name.
     */
    public void registerExternalClient(String providerName, McpSyncClient client) {
        log.info("Registering external MCP client provider: {}", providerName);
        externalClients.put(providerName, client);
    }

    /**
     * Removes an external MCP client connection.
     */
    public void unregisterExternalClient(String providerName) {
        externalClients.remove(providerName);
    }

    /**
     * Discovers and aggregates all tools across Polaris Core and all external MCP providers.
     */
    public List<Tool> discoverAllTools() {
        List<Tool> allTools = new ArrayList<>();

        // 1. Discover tools from Polaris Core
        try {
            allTools.addAll(polarisMcpClient.listAvailableTools());
        } catch (Exception e) {
            log.error("Failed to load tools from Polaris Core: {}", e.getMessage());
        }

        // 2. Discover tools from registered external MCP providers
        for (Map.Entry<String, McpSyncClient> entry : externalClients.entrySet()) {
            try {
                if (entry.getValue() != null && entry.getValue().isInitialized()) {
                    allTools.addAll(entry.getValue().listTools().tools());
                }
            } catch (Exception e) {
                log.error("Failed to load tools from external MCP provider '{}': {}", entry.getKey(), e.getMessage());
            }
        }

        return Collections.unmodifiableList(allTools);
    }

    /**
     * Dispatches a tool invocation to the appropriate MCP provider.
     */
    public CallToolResult executeTool(String toolName, Map<String, Object> arguments) {
        log.info("Dispatching execution for tool: {}", toolName);

        // Check if Polaris Core handles this tool
        List<Tool> polarisTools = polarisMcpClient.listAvailableTools();
        boolean isPolarisTool = polarisTools.stream().anyMatch(t -> t.name().equals(toolName));

        if (isPolarisTool) {
            return polarisMcpClient.callTool(toolName, arguments);
        }

        // Search across external providers
        for (Map.Entry<String, McpSyncClient> entry : externalClients.entrySet()) {
            try {
                McpSyncClient client = entry.getValue();
                if (client != null && client.isInitialized()) {
                    boolean toolFound = client.listTools().tools().stream().anyMatch(t -> t.name().equals(toolName));
                    if (toolFound) {
                        return client.callTool(new CallToolRequest(toolName, arguments != null ? arguments : Map.of()));
                    }
                }
            } catch (Exception e) {
                log.error("External provider '{}' failed executing tool '{}': {}", entry.getKey(), toolName, e.getMessage());
            }
        }

        // Fallback: tool unknown
        log.warn("Tool '{}' not recognized by any registered MCP provider", toolName);
        return new CallToolResult(
                List.of(new TextContent("Error: Tool '" + toolName + "' is not supported by any registered MCP server.")),
                true,
                null,
                Map.of()
        );
    }
}
