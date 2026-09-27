package vn.danang.polaris.assistant.service;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.tools.ToolResult;

/**
 * Verifies {@link AssistantChatService#toToolTurn} surfaces a {@link ToolResult}'s remedy
 * {@code actions[]} into conversation history (WO-022 Task 6) — the last leg of the seam, so the
 * data WO-022 threads through the MCP boundary (see {@code PolicyToolManagerRemedyActionsTest})
 * isn't thrown away once it reaches the Assistant's own conversation history.
 */
class AssistantChatServiceToToolTurnTest {

    private AssistantChatService chatService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AssistantModelClient modelClient = mock(AssistantModelClient.class);
        IntentResolutionFacade intentResolutionFacade = mock(IntentResolutionFacade.class);
        ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        chatService = new AssistantChatService(modelClient, tracerProvider, intentResolutionFacade, new ObjectMapper());
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a ToolResult carrying remedy actions, when converted to a tool turn, then the AssistantMessage carries widgetType=PROBLEM_CARD and the actions in its widgetPayload")
        void toToolTurn_withActions_setsProblemCardWidget() {
            ToolCall toolCall = new ToolCall("place_order", Map.of());
            List<Map<String, Object>> actions = List.of(
                    Map.of("label", "Remove Item", "action", "remove_item", "sku", "NG-WATCH-01"));
            ToolResult result = ToolResult.error(toolCall, "Insufficient stock", "insufficient stock", actions);

            AssistantMessage turn = chatService.toToolTurn(result);

            assertThat(turn.getWidgetType()).isEqualTo("PROBLEM_CARD");
            assertThat(turn.getWidgetPayload()).contains("remove_item").contains("NG-WATCH-01");
        }
    }

    // =========================================================================
    // 2. Invalid input
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & denials")
    class InvalidInput {

        @Test
        @DisplayName("Given a ToolResult with no actions (e.g. a plain success), when converted, then no widget is attached")
        void toToolTurn_withoutActions_leavesWidgetTypeNull() {
            ToolCall toolCall = new ToolCall("search_available_products", Map.of());
            ToolResult result = ToolResult.success(toolCall, "2 results found");

            AssistantMessage turn = chatService.toToolTurn(result);

            assertThat(turn.getWidgetType()).isNull();
            assertThat(turn.getWidgetPayload()).isNull();
        }

        @Test
        @DisplayName("Given an unrelated policy denial with no actions, when converted, then no widget is attached")
        void toToolTurn_deniedWithoutActions_leavesWidgetTypeNull() {
            ToolCall toolCall = new ToolCall("cancel_order", Map.of());
            ToolResult result = ToolResult.denied(toolCall, "Scope missing");

            AssistantMessage turn = chatService.toToolTurn(result);

            assertThat(turn.getWidgetType()).isNull();
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given a ToolResult with an empty (non-null) actions list, when converted, then no widget is attached")
        void toToolTurn_withEmptyActionsList_leavesWidgetTypeNull() {
            ToolCall toolCall = new ToolCall("place_order", Map.of());
            ToolResult result = ToolResult.error(toolCall, "error", "note", List.of());

            AssistantMessage turn = chatService.toToolTurn(result);

            assertThat(turn.getWidgetType()).isNull();
            assertThat(turn.getWidgetPayload()).isNull();
        }

        @Test
        @DisplayName("Given a ToolResult carrying actions, when converted, then the tool turn's role/content/toolCallId are still populated as before")
        void toToolTurn_withActions_stillPopulatesBaseFields() {
            ToolCall toolCall = new ToolCall("place_order", Map.of());
            List<Map<String, Object>> actions = List.of(Map.of("label", "Remove Item", "action", "remove_item"));
            ToolResult result = ToolResult.error(toolCall, "Insufficient stock", "note", actions);

            AssistantMessage turn = chatService.toToolTurn(result);

            assertThat(turn.getRole().name()).isEqualTo("TOOL");
            assertThat(turn.getToolCallId()).isEqualTo("place_order");
            assertThat(turn.getContent()).isEqualTo("Insufficient stock");
            assertThat(turn.getCreatedAt()).isNotNull();
        }
    }
}
