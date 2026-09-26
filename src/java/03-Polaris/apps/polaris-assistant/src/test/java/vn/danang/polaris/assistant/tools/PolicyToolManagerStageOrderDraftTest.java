package vn.danang.polaris.assistant.tools;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.dto.OrderItemRequest;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftStatus;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.service.DraftStagingService;
import vn.danang.polaris.assistant.service.DraftStagingService.StageOutcome;

/**
 * Verifies {@link PolicyToolManager}'s local interception of {@code stage_order_draft}
 * (WO-020 Task 6) — routing to {@link DraftStagingService} only after the same policy gate every
 * other tool call goes through, and the exact {@link ToolResult} shape (the {@code data}/
 * {@code actions} fields {@code AssistantChatService#streamTurn} builds its SSE frames from).
 */
@ExtendWith(MockitoExtension.class)
class PolicyToolManagerStageOrderDraftTest {

    @Mock
    private PolarisMcpClient polarisMcpClient;

    @Mock
    private DraftStagingService draftStagingService;

    private PolicyToolManager toolManager;

    @BeforeEach
    void setUp() {
        toolManager = new PolicyToolManager(polarisMcpClient, null, null, draftStagingService);
    }

    private ToolExecutionContext contextFor(String toolName) {
        Tool tool = Tool.builder(toolName, Map.of()).build();
        ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
        when(resolvedIntent.intentId()).thenReturn("commerce.order.place");
        when(resolvedIntent.meetsThreshold()).thenReturn(true);
        when(resolvedIntent.filteredTools()).thenReturn(List.of(tool));
        return new ToolExecutionContext("sess-1", "user-1", 1, resolvedIntent);
    }

    private AssistantOrderDraft draft(String id) {
        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId(id);
        draft.setSessionId("sess-1");
        draft.setCustomerId(42L);
        draft.setStatus(AssistantOrderDraftStatus.WAITING_CONFIRMATION);
        draft.setTotalAmount(new BigDecimal("49.80"));
        draft.setExpiresAt(Instant.now().plusSeconds(900));
        return draft;
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given discoverAllTools, when called, then the merged list includes stage_order_draft alongside the remote tools")
        void discoverAllTools_includesStageOrderDraft() {
            when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(Tool.builder("place_order", Map.of()).build()));

            List<Tool> tools = toolManager.discoverAllTools();

            assertThat(tools).extracting(Tool::name).contains("place_order", "stage_order_draft");
        }

        @Test
        @DisplayName("Given a valid staging request, when handled, then it never reaches PolarisMcpClient and returns a SUCCESS ToolResult carrying the draft summary in data()")
        void handleToolCalls_staged_neverDispatchesToMcpAndCarriesDraftData() {
            when(draftStagingService.stage(eq("sess-1"), eq("user-1"), eq(42L), isNull(), anyList()))
                    .thenReturn(new StageOutcome.Staged(draft("dft-abc"), List.of()));

            ToolCall toolCall = new ToolCall("stage_order_draft", Map.of(
                    "customer_id", 42, "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 2))));

            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("stage_order_draft"));

            assertThat(results).hasSize(1);
            ToolResult result = results.getFirst();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.data()).containsEntry("draftId", "dft-abc").containsEntry("status", "WAITING_CONFIRMATION");
            assertThat(result.actions()).isNull();
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }
    }

    // =========================================================================
    // 2. Invalid input
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("Given customer_id is missing, when handled, then an ERROR ToolResult is returned without ever calling DraftStagingService")
        void handleToolCalls_missingCustomerId_returnsErrorWithoutCallingService() {
            ToolCall toolCall = new ToolCall("stage_order_draft", Map.of(
                    "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 2))));

            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("stage_order_draft"));

            assertThat(results.getFirst().isError()).isTrue();
            verify(draftStagingService, never()).stage(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Given items is missing, when handled, then an ERROR ToolResult is returned")
        void handleToolCalls_missingItems_returnsError() {
            ToolCall toolCall = new ToolCall("stage_order_draft", Map.of("customer_id", 42));

            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("stage_order_draft"));

            assertThat(results.getFirst().isError()).isTrue();
        }

        @Test
        @DisplayName("Given an item with a non-positive quantity, when handled, then an ERROR ToolResult is returned")
        void handleToolCalls_invalidQuantity_returnsError() {
            ToolCall toolCall = new ToolCall("stage_order_draft", Map.of(
                    "customer_id", 42, "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 0))));

            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("stage_order_draft"));

            assertThat(results.getFirst().isError()).isTrue();
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given DraftStagingService returns Rejected, when handled, then an ERROR ToolResult carries the remedy actions and problem data")
        void handleToolCalls_rejected_returnsErrorWithActionsAndProblemData() {
            List<Map<String, Object>> actions = List.of(Map.of("label", "Remove Item", "action", "remove_item", "sku", "NG-EARBUD-01"));
            when(draftStagingService.stage(any(), any(), any(), any(), any()))
                    .thenReturn(new StageOutcome.Rejected("NG-EARBUD-01", 10, 3, actions));

            ToolCall toolCall = new ToolCall("stage_order_draft", Map.of(
                    "customer_id", 42, "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 10))));

            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("stage_order_draft"));

            ToolResult result = results.getFirst();
            assertThat(result.isError()).isTrue();
            assertThat(result.actions()).isEqualTo(actions);
            assertThat(result.data()).containsEntry("sku", "NG-EARBUD-01")
                    .containsEntry("requested_quantity", 10)
                    .containsEntry("available_quantity", 3);
        }

        @Test
        @DisplayName("Given DraftStagingService throws (Catalog unreachable), when handled, then it fails closed with an ERROR ToolResult rather than propagating")
        void handleToolCalls_serviceThrows_failsClosedWithErrorToolResult() {
            when(draftStagingService.stage(any(), any(), any(), any(), any()))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));

            ToolCall toolCall = new ToolCall("stage_order_draft", Map.of(
                    "customer_id", 42, "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 1))));

            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), contextFor("stage_order_draft"));

            assertThat(results.getFirst().isError()).isTrue();
        }

        @Test
        @DisplayName("Given the intent gate denies stage_order_draft, when handled, then DraftStagingService is never called")
        void handleToolCalls_deniedByIntentGate_neverCallsDraftStagingService() {
            Tool otherTool = Tool.builder("place_order", Map.of()).build();
            ResolvedIntent resolvedIntent = mock(ResolvedIntent.class);
            when(resolvedIntent.intentId()).thenReturn("catalog.product.search");
            when(resolvedIntent.meetsThreshold()).thenReturn(true);
            when(resolvedIntent.filteredTools()).thenReturn(List.of(otherTool));
            ToolExecutionContext context = new ToolExecutionContext("sess-1", "user-1", 1, resolvedIntent);

            ToolCall toolCall = new ToolCall("stage_order_draft", Map.of(
                    "customer_id", 42, "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 1))));

            List<ToolResult> results = toolManager.handleToolCalls(List.of(toolCall), context);

            assertThat(results.getFirst().isError()).isTrue();
            verify(draftStagingService, never()).stage(any(), any(), any(), any(), any());
        }
    }
}
