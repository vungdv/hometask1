package vn.danang.polaris.assistant.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.intent.ResolvedIntent;

/**
 * Verifies {@link PolicyToolManager} reads {@code structuredContent.actions[]} back out of a
 * remote MCP tool call's {@link CallToolResult} into {@link ToolResult#actions()} (WO-022 Task 5)
 * — the consuming side of the seam whose producing side is {@code OrderMcpTools.placeOrder}
 * (apps/polaris). Together with {@code McpServerTest}'s producer-side assertion, this is what
 * proves {@code actions[]} actually crosses the MCP boundary end-to-end, not just that each side
 * independently agrees with itself.
 */
@ExtendWith(MockitoExtension.class)
class PolicyToolManagerRemedyActionsTest {

    @Mock
    private PolarisMcpClient polarisMcpClient;

    private PolicyToolManager toolManager;

    @BeforeEach
    void setUp() {
        toolManager = new PolicyToolManager(polarisMcpClient);
    }

    private ToolExecutionContext contextFor(String toolName) {
        Tool tool = Tool.builder(toolName, Map.of()).build();
        ResolvedIntent resolvedIntent = org.mockito.Mockito.mock(ResolvedIntent.class);
        when(resolvedIntent.intentId()).thenReturn("commerce.order.place");
        when(resolvedIntent.meetsThreshold()).thenReturn(true);
        when(resolvedIntent.filteredTools()).thenReturn(List.of(tool));
        return new ToolExecutionContext("sess-1", "user-1", 1, resolvedIntent);
    }

    /** The exact shape OrderMcpTools.placeOrder attaches (mirrors InsufficientStockActions.build's output). */
    private static Map<String, Object> outOfStockStructuredContent() {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("type", "https://polaris.local/errors/out-of-stock");
        structured.put("sku", "NG-WATCH-01");
        structured.put("requested_quantity", 10);
        structured.put("available_quantity", 5);
        structured.put("actions", List.of(
                Map.of("label", "Adjust Quantity to 5", "action", "adjust_quantity", "sku", "NG-WATCH-01", "quantity", 5),
                Map.of("label", "Search Alternatives", "action", "search_alternatives", "query", "NG-WATCH-01"),
                Map.of("label", "Remove Item", "action", "remove_item", "sku", "NG-WATCH-01")));
        return structured;
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given place_order's MCP result carries structuredContent.actions, when handled, then ToolResult.actions() carries the same three entries byte-for-byte")
        void handleToolCalls_remoteErrorWithStructuredActions_populatesToolResultActions() {
            Map<String, Object> structured = outOfStockStructuredContent();
            CallToolResult mcpResult = CallToolResult.builder()
                    .addTextContent("Insufficient stock for product 'NG-WATCH-01'. Requested: 10, available: 5.")
                    .structuredContent(structured)
                    .isError(true)
                    .build();
            when(polarisMcpClient.callTool("place_order", Map.of())).thenReturn(mcpResult);

            ToolCall toolCall = new ToolCall("place_order", Map.of());
            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("place_order"));

            ToolResult result = results.getFirst();
            assertThat(result.isError()).isTrue();
            assertThat(result.result()).contains("Insufficient stock for product 'NG-WATCH-01'");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> expectedActions = (List<Map<String, Object>>) structured.get("actions");
            assertThat(result.actions()).isEqualTo(expectedActions);
            assertThat(result.actions()).extracting(a -> a.get("action"))
                    .containsExactly("adjust_quantity", "search_alternatives", "remove_item");
        }

        @Test
        @DisplayName("Given place_order succeeds, when handled, then ToolResult.actions() is null - the successful path is untouched")
        void handleToolCalls_remoteSuccess_leavesActionsNull() {
            CallToolResult mcpResult = CallToolResult.builder()
                    .addTextContent("Order placed: ORD-1001")
                    .isError(false)
                    .build();
            when(polarisMcpClient.callTool("place_order", Map.of())).thenReturn(mcpResult);

            ToolCall toolCall = new ToolCall("place_order", Map.of());
            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("place_order"));

            ToolResult result = results.getFirst();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.actions()).isNull();
        }
    }

    // =========================================================================
    // 2. Invalid input
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("Given an error result with no structuredContent at all, when handled, then ToolResult.actions() falls back to null rather than throwing")
        void handleToolCalls_remoteErrorWithoutStructuredContent_fallsBackToNullActions() {
            CallToolResult mcpResult = CallToolResult.builder()
                    .addTextContent("Order not found")
                    .isError(true)
                    .build();
            when(polarisMcpClient.callTool("cancel_order", Map.of())).thenReturn(mcpResult);

            ToolCall toolCall = new ToolCall("cancel_order", Map.of());
            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("cancel_order"));

            ToolResult result = results.getFirst();
            assertThat(result.isError()).isTrue();
            assertThat(result.actions()).isNull();
        }

        @Test
        @DisplayName("Given structuredContent is present but isn't shaped like a Map, when handled, then ToolResult.actions() falls back to null rather than throwing")
        void handleToolCalls_remoteErrorWithUnshapedStructuredContent_fallsBackToNullActions() {
            CallToolResult mcpResult = CallToolResult.builder()
                    .addTextContent("Something went wrong")
                    .structuredContent("just a plain string, not a map")
                    .isError(true)
                    .build();
            when(polarisMcpClient.callTool("place_order", Map.of())).thenReturn(mcpResult);

            ToolCall toolCall = new ToolCall("place_order", Map.of());
            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("place_order"));

            assertThat(results.getFirst().actions()).isNull();
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given structuredContent is a Map but has no 'actions' key, when handled, then ToolResult.actions() is null")
        void handleToolCalls_structuredContentWithoutActionsKey_yieldsNullActions() {
            CallToolResult mcpResult = CallToolResult.builder()
                    .addTextContent("Customer not found")
                    .structuredContent(Map.of("type", "https://polaris.local/errors/not-found"))
                    .isError(true)
                    .build();
            when(polarisMcpClient.callTool("place_order", Map.of())).thenReturn(mcpResult);

            ToolCall toolCall = new ToolCall("place_order", Map.of());
            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("place_order"));

            assertThat(results.getFirst().actions()).isNull();
        }

        @Test
        @DisplayName("Given the 'actions' entry is present but empty, when handled, then ToolResult.actions() is null rather than an empty list")
        void handleToolCalls_emptyActionsList_yieldsNullActions() {
            CallToolResult mcpResult = CallToolResult.builder()
                    .addTextContent("Insufficient stock")
                    .structuredContent(Map.of("actions", List.of()))
                    .isError(true)
                    .build();
            when(polarisMcpClient.callTool("place_order", Map.of())).thenReturn(mcpResult);

            ToolCall toolCall = new ToolCall("place_order", Map.of());
            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("place_order"));

            assertThat(results.getFirst().actions()).isNull();
        }
    }
}
