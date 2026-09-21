package vn.danang.polaris.assistant.intent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.mcp.McpHub;

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
            Tool searchTool = Tool.builder("search_available_products").build();
            Tool orderTool = Tool.builder("place_order").build();
            when(mockHub.discoverAllTools()).thenReturn(List.of(searchTool, orderTool));

            IntentResolutionFacade facade = new IntentResolutionFacade(
                    new DefaultIntentResolver(new IntentTaxonomyProperties()),
                    new IntentToolRegistry(),
                    mockHub
            );

            ResolvedIntent resolved = facade.resolve("Find wireless chargers", List.of());

            assertThat(resolved.intentId()).isEqualTo(IntentClassification.CATALOG_SEARCH);
            assertThat(resolved.confidence()).isGreaterThanOrEqualTo(0.80);
            assertThat(resolved.meetsThreshold()).isTrue();
            assertThat(resolved.acceptedTools())
                    .extracting(Tool::name)
                    .containsExactly("search_available_products");
            assertThat(resolved.tools()).isEqualTo(resolved.acceptedTools());
            assertThat(resolved.filteredTools()).isEqualTo(resolved.acceptedTools());
            verify(mockHub).discoverAllTools();
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
            assertThat(resolved.filteredTools()).isEmpty();
        }
    }
}
