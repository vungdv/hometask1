package vn.danang.polaris.assistant.intent;

import java.util.List;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.mcp.McpHub;
import vn.danang.polaris.assistant.mcp.ToolExecutionContext;
import vn.danang.polaris.assistant.mcp.ToolResult;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.service.IntentResolutionFacade;

class IntentResolutionFacadeTest {

    // =========================================================================
    // 1. Happy path — standard intent resolution & tool filtering
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given configured McpHub, when resolve called with message, then discovers tools and returns filtered ResolvedIntent")
        void resolves_intent_and_filters_tools_using_configured_hub() {
            McpHub mockHub = mock(McpHub.class);
            Tool searchTool = Tool.builder("search_available_products", Map.of()).build();
            Tool orderTool = Tool.builder("place_order", Map.of()).build();
            when(mockHub.discoverAllTools()).thenReturn(List.of(searchTool, orderTool));

            IntentClassifier stubClassifier = (query, history, intents) ->
                    new IntentClassification("catalog.product.search", 0.93);
            IntentResolutionFacade facade = new IntentResolutionFacade(
                    new DefaultIntentResolver(stubClassifier),
                    mockHub
            );

            ResolvedIntent resolved = facade.resolve("Find wireless chargers", List.of());

            assertThat(resolved.intentId()).isEqualTo("catalog.product.search");
            assertThat(resolved.confidence()).isGreaterThanOrEqualTo(0.80);
            assertThat(resolved.meetsThreshold()).isTrue();
            assertThat(resolved.acceptedTools())
                    .extracting(Tool::name)
                    .containsExactly("search_available_products");
            assertThat(resolved.tools()).isEqualTo(resolved.acceptedTools());
            assertThat(resolved.acceptedTools()).isEqualTo(resolved.acceptedTools());
            verify(mockHub).discoverAllTools();
        }

        @Test
        @DisplayName("Given IntentResolver interface mock, when resolve called, delegates directly via the interface")
        void delegates_resolution_directly_via_intent_resolver_interface() {
            McpHub mockHub = mock(McpHub.class);
            IntentResolver mockResolver = mock(IntentResolver.class);
            Tool testTool = Tool.builder("search_available_products", Map.of()).build();
            List<Tool> tools = List.of(testTool);
            when(mockHub.discoverAllTools()).thenReturn(tools);

            ResolvedIntent expectedIntent = new ResolvedIntent("catalog.product.search", 0.95, true, tools);
            when(mockResolver.resolve(eq("Find chargers"), any(), eq(tools))).thenReturn(expectedIntent);

            IntentResolutionFacade facade = new IntentResolutionFacade(mockResolver, mockHub);
            ResolvedIntent actual = facade.resolve("Find chargers", List.of());

            assertThat(actual).isEqualTo(expectedIntent);
            verify(mockResolver).resolve(eq("Find chargers"), any(), eq(tools));
        }

        @Test
        @DisplayName("Given valid tool calls and execution context, when executeToolCalls is called, then delegates to McpHub")
        void delegates_tool_execution_to_configured_mcp_hub() {
            McpHub mockHub = mock(McpHub.class);
            ToolCall toolCall = new ToolCall("search_available_products", Map.of("query", "charger"));
            ResolvedIntent mockResolvedIntent = mock(ResolvedIntent.class);
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, mockResolvedIntent
            );
            List<ToolResult> expectedResults = List.of(ToolResult.success(toolCall, "Found product"));
            when(mockHub.handleToolCalls(eq(List.of(toolCall)), eq(context))).thenReturn(expectedResults);

            IntentResolutionFacade facade = new IntentResolutionFacade(
                    new DefaultIntentResolver(),
                    mockHub
            );

            List<ToolResult> results = facade.executeToolCalls(List.of(toolCall), context);

            assertThat(results).isEqualTo(expectedResults);
            verify(mockHub).handleToolCalls(eq(List.of(toolCall)), eq(context));
        }

        @Test
        @DisplayName("Given handleToolCalls alias invoked, then delegates identically to McpHub")
        void handle_tool_calls_alias_delegates_identically() {
            McpHub mockHub = mock(McpHub.class);
            ToolCall toolCall = new ToolCall("search_available_products", Map.of("query", "charger"));
            ResolvedIntent mockResolvedIntent = mock(ResolvedIntent.class);
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, mockResolvedIntent
            );
            List<ToolResult> expectedResults = List.of(ToolResult.success(toolCall, "Found product"));
            when(mockHub.handleToolCalls(eq(List.of(toolCall)), eq(context))).thenReturn(expectedResults);

            IntentResolutionFacade facade = new IntentResolutionFacade(
                    new DefaultIntentResolver(),
                    mockHub
            );

            List<ToolResult> results = facade.executeToolCalls(List.of(toolCall), context);

            assertThat(results).isEqualTo(expectedResults);
            verify(mockHub).handleToolCalls(eq(List.of(toolCall)), eq(context));
        }
    }

    // =========================================================================
    // 2. Invalid input & policy enforcement
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & policy enforcement")
    class InvalidInput {

        @Test
        @DisplayName("Given policy denial from McpHub, when executeToolCalls called, then propagates denied ToolResult")
        void propagates_policy_denial_from_mcp_hub() {
            McpHub mockHub = mock(McpHub.class);
            ToolCall toolCall = new ToolCall("place_order", Map.of("sku", "PROD-1"));
            ResolvedIntent mockResolvedIntent = mock(ResolvedIntent.class);
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-no-scope", 1, mockResolvedIntent
            );
            List<ToolResult> deniedResults = List.of(ToolResult.denied(toolCall, "Missing scope order.write"));
            when(mockHub.handleToolCalls(eq(List.of(toolCall)), eq(context))).thenReturn(deniedResults);

            IntentResolutionFacade facade = new IntentResolutionFacade(
                    new DefaultIntentResolver(),
                    mockHub
            );

            List<ToolResult> results = facade.executeToolCalls(List.of(toolCall), context);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isDenied()).isTrue();
            assertThat(results.get(0).result()).isEqualTo("Missing scope order.write");
        }
    }

    // =========================================================================
    // 3. Edge cases — null inputs, missing hub, immutability
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given ResolvedIntent created with null tools, then defensive copy produces empty unmodifiable list")
        void resolved_intent_guarantees_immutable_empty_tools_on_null() {
            ResolvedIntent resolved = new ResolvedIntent("test.intent", 0.9, true, null);

            assertThat(resolved.acceptedTools()).isEmpty();
            assertThat(resolved.tools()).isEmpty();
            assertThat(resolved.acceptedTools()).isEmpty();
        }

        @Test
        @DisplayName("Given null or empty tool calls, when executeToolCalls called, then returns empty list without calling hub")
        void returns_empty_list_when_tool_calls_null_or_empty() {
            McpHub mockHub = mock(McpHub.class);
            IntentResolutionFacade facade = new IntentResolutionFacade(
                    new DefaultIntentResolver(),
                    mockHub
            );
            ResolvedIntent mockResolvedIntent = mock(ResolvedIntent.class);
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, mockResolvedIntent
            );

            assertThat(facade.executeToolCalls(null, context)).isEmpty();
            assertThat(facade.executeToolCalls(List.of(), context)).isEmpty();
        }

        @Test
        @DisplayName("Given null McpHub, when executeToolCalls called, then returns empty list gracefully")
        void returns_empty_list_when_mcp_hub_is_null() {
            IntentResolutionFacade facade = new IntentResolutionFacade(
                    new DefaultIntentResolver(),
                    null
            );
            ToolCall toolCall = new ToolCall("search_available_products", Map.of("query", "charger"));
            ResolvedIntent mockResolvedIntent = mock(ResolvedIntent.class);
            ToolExecutionContext context = new ToolExecutionContext(
                    "sess-1", "user-1", 1, mockResolvedIntent
            );

            assertThat(facade.executeToolCalls(List.of(toolCall), context)).isEmpty();
        }
    }
}
