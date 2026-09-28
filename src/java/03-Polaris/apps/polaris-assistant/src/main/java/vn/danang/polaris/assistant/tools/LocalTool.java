package vn.danang.polaris.assistant.tools;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * A model-callable tool implemented inside the assistant itself rather than by a remote MCP server
 * (e.g. staging an order draft, which only touches the assistant's own tables).
 * <p>
 * Local tools are offered and authorized exactly like remote ones: {@link PolicyToolManager} lists
 * their {@link #definition()} alongside the discovered MCP tools, runs the same intent and per-tool
 * scope checks ({@code intents.json}), and only then dispatches to {@link #execute} instead of MCP.
 */
public interface LocalTool {

    /**
     * @return the tool's name, description and input schema, in the same shape the model sees for MCP tools
     */
    Tool definition();

    default String name() {
        return definition().name();
    }

    /**
     * Runs an already-authorized call. Implementations report failures as {@link ToolResult#error} or
     * {@link ToolResult#denied} rather than throwing.
     *
     * @param toolCall the model's call
     * @param context  session, caller and intent of the turn
     */
    ToolResult execute(ToolCall toolCall, ToolExecutionContext context);
}
