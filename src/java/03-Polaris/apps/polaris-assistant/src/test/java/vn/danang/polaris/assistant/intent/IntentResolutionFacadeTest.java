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

        @Test
        @DisplayName("Given explicit hub override, when resolve called, then discovers tools from specified hub")
        void resolves_intent_using_explicit_hub_override() {
            McpHub customHub = mock(McpHub.class);
            Tool tool = Tool.builder("search_available_products").build();
            when(customHub.discoverAllTools()).thenReturn(List.of(tool));

            IntentResolutionFacade facade = new IntentResolutionFacade();
            ResolvedIntent resolved = facade.resolve("Find phones", List.of(), customHub);

            assertThat(resolved.acceptedTools())
                    .extracting(Tool::name)
                    .containsExactly("search_available_products");
            verify(customHub).discoverAllTools();
        }

        @Test
        @DisplayName("Given pre-discovered tools, when resolve called, then directly filters pre-discovered tools")
        void resolves_intent_with_pre_discovered_tools() {
            Tool tool = Tool.builder("search_available_products").build();
            IntentResolutionFacade facade = new IntentResolutionFacade();

            ResolvedIntent resolved = facade.resolve("Find headphones", List.of(), List.of(tool));

            assertThat(resolved.intentId()).isEqualTo(IntentClassification.CATALOG_SEARCH);
            assertThat(resolved.acceptedTools())
                    .extracting(Tool::name)
                    .containsExactly("search_available_products");
        }

        @Test
        @DisplayName("Given getters called, then exposes underlying IntentResolver and IntentToolRegistry")
        void exposes_underlying_resolver_and_registry() {
            IntentResolver resolver = mock(IntentResolver.class);
            IntentToolRegistry registry = mock(IntentToolRegistry.class);

            IntentResolutionFacade facade = new IntentResolutionFacade(resolver, registry);

            assertThat(facade.getIntentResolver()).isSameAs(resolver);
            assertThat(facade.getIntentToolRegistry()).isSameAs(registry);
        }
    }

    // =========================================================================
    // 2. Invalid input & low confidence
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & low confidence")
    class InvalidInput {

        @Test
        @DisplayName("Given intent confidence below threshold, when resolve called, then falls back to all available tools")
        void falls_back_to_all_available_tools_when_confidence_below_threshold() {
            IntentResolver mockResolver = mock(IntentResolver.class);
            when(mockResolver.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(new IntentClassification(IntentClassification.ORDER_PLACE, 0.60));

            Tool placeTool = Tool.builder("place_order").build();
            Tool searchTool = Tool.builder("search_available_products").build();

            IntentResolutionFacade facade = new IntentResolutionFacade(mockResolver, new IntentToolRegistry());
            ResolvedIntent resolved = facade.resolve("maybe buy", List.of(), List.of(placeTool, searchTool));

            assertThat(resolved.intentId()).isEqualTo(IntentClassification.ORDER_PLACE);
            assertThat(resolved.confidence()).isEqualTo(0.60);
            assertThat(resolved.meetsThreshold()).isFalse();
            assertThat(resolved.acceptedTools()).containsExactly(placeTool, searchTool);
        }

        @Test
        @DisplayName("Given general conversation intent, when resolve called, then returns empty accepted tools")
        void returns_empty_accepted_tools_for_general_conversation() {
            Tool tool = Tool.builder("search_available_products").build();
            IntentResolutionFacade facade = new IntentResolutionFacade();

            ResolvedIntent resolved = facade.resolve("Hello! How are you?", List.of(), List.of(tool));

            assertThat(resolved.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
            assertThat(resolved.acceptedTools()).isEmpty();
        }
    }

    // =========================================================================
    // 3. Edge cases — null inputs, missing hub, immutability
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given null history, when resolve called, then handles gracefully without NullPointerException")
        void handles_null_history_gracefully() {
            IntentResolutionFacade facade = new IntentResolutionFacade();
            ResolvedIntent resolved = facade.resolve("Hello", null);

            assertThat(resolved).isNotNull();
            assertThat(resolved.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
        }

        @Test
        @DisplayName("Given null McpHub and empty constructor, when resolve called, then defaults to empty tools safely")
        void defaults_to_empty_tools_when_hub_is_absent() {
            IntentResolutionFacade facade = new IntentResolutionFacade();
            ResolvedIntent resolved = facade.resolve("Find fast chargers", List.of());

            assertThat(resolved).isNotNull();
            assertThat(resolved.acceptedTools()).isEmpty();
        }

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
