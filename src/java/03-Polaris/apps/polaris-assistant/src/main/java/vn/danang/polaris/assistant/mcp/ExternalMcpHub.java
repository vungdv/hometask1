package vn.danang.polaris.assistant.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;

/**
 * Hub and tool registry for Polaris Assistant.
 * Dispatches tool execution calls directly to Polaris Core via {@link PolarisMcpClient}
 * with distributed tracing.
 */
@Component
public class ExternalMcpHub {

    private static final Logger log = LoggerFactory.getLogger(ExternalMcpHub.class);

    private final PolarisMcpClient polarisMcpClient;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public ExternalMcpHub(PolarisMcpClient polarisMcpClient, ObjectProvider<Tracer> tracerProvider) {
        this.polarisMcpClient = polarisMcpClient;
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
    }

    public ExternalMcpHub(PolarisMcpClient polarisMcpClient) {
        this(polarisMcpClient, (Tracer) null);
    }

    public ExternalMcpHub(PolarisMcpClient polarisMcpClient, @Nullable Tracer tracer) {
        this.polarisMcpClient = polarisMcpClient;
        this.tracer = tracer;
    }

    /**
     * Discovers all tools available from Polaris Core.
     */
    public List<Tool> discoverAllTools() {
        return polarisMcpClient.listAvailableTools();
    }

    /**
     * Dispatches a tool invocation to Polaris Core.
     * Records a child distributed trace span ('mcp.tool_call <tool_name>') with provider, tool, and status tags.
     */
    public CallToolResult executeTool(String toolName, Map<String, Object> arguments) {
        log.info("Dispatching execution for tool: {}", toolName);

        if (this.tracer == null) {
            return polarisMcpClient.callTool(toolName, arguments);
        }
        
        String spanName = "mcp.tool_call %s".formatted(Optional.ofNullable(toolName).orElse("unknown"));
        Span span = this.tracer.nextSpan().name(spanName);
        if (toolName != null) {
            span.tag("mcp.tool.name", toolName);
        }
        span.tag("mcp.provider", "polaris-core");
        span.start();

        try (Tracer.SpanInScope ws = this.tracer.withSpan(span)) {
            CallToolResult result = polarisMcpClient.callTool(toolName, arguments);
            if (result != null && Boolean.TRUE.equals(result.isError())) {
                span.tag("error", "true");
            }
            return result;
        } catch (Exception ex) {
            span.error(ex);
            span.tag("error", "true");
            throw ex;
        } finally {
            span.end();
        }
    }
}
