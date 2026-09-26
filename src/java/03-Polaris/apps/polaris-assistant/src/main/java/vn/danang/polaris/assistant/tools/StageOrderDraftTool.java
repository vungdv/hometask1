package vn.danang.polaris.assistant.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Tool schema for {@code stage_order_draft} — a <b>local</b> tool intercepted inside
 * {@code apps/polaris-assistant} before MCP dispatch (see {@link PolicyToolManager}), never a new
 * MCP tool on {@code apps/polaris}. Matches the tool ADR-0004 §4.A's "Provider Abstraction
 * Contract" lists as {@code stage_order}, spelled out fully here for clarity in code.
 */
public final class StageOrderDraftTool {

    public static final String TOOL_STAGE_ORDER_DRAFT = "stage_order_draft";

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "draft_id": {
              "type": "string",
              "description": "Existing draft ID to update in place (e.g. after adjusting quantity or removing an item). Omit to start a new draft."
            },
            "customer_id": {
              "type": "integer",
              "description": "Numeric customer ID (use this or customer_name)"
            },
            "customer_name": {
              "type": "string",
              "description": "Customer name for fuzzy lookup, same semantics as place_order"
            },
            "items": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "sku": { "type": "string" },
                  "quantity": { "type": "integer" }
                },
                "required": ["sku", "quantity"]
              }
            }
          },
          "required": ["items"]
        }
        """;

    private StageOrderDraftTool() {
    }

    public static McpSchema.Tool definition(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder(TOOL_STAGE_ORDER_DRAFT, jsonMapper, SCHEMA)
                .description("Read-only stock verification and draft staging for a multi-item order, "
                        + "awaiting explicit shopper confirmation before any order is placed.")
                .build();
    }

    public static McpSchema.Tool definition() {
        return definition(new JacksonMcpJsonMapper(new ObjectMapper()));
    }
}
