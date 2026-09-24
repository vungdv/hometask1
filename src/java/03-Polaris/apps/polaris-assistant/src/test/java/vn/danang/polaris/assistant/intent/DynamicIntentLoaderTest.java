package vn.danang.polaris.assistant.intent;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

class DynamicIntentLoaderTest {

    private DynamicIntentLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DynamicIntentLoader();
    }

    // =========================================================================
    // 1. Happy path — standard dynamic JSON loading
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid JSON array string, when loaded, then parses into IntentDefinition list")
        void loads_intents_from_valid_json_string() {
            String json = """
                    [
                      {
                        "id": "custom.order.track",
                        "description": "Track a custom order",
                        "examples": ["track custom order"],
                        "allowedTools": ["get_order_status"],
                        "requiredScope": "order.read",
                        "confidenceThreshold": 0.85,
                        "mutating": false
                      }
                    ]
                    """;

            List<IntentDefinition> intents = loader.loadFromJson(json);

            assertThat(intents).hasSize(1);
            IntentDefinition def = intents.get(0);
            assertThat(def.getId()).isEqualTo("custom.order.track");
            assertThat(def.getDescription()).isEqualTo("Track a custom order");
            assertThat(def.getExamples()).containsExactly("track custom order");
            assertThat(def.getAllowedTools()).containsExactly("get_order_status");
            assertThat(def.getRequiredScope()).isEqualTo("order.read");
            assertThat(def.getConfidenceThreshold()).isEqualTo(0.85);
            assertThat(def.isMutating()).isFalse();
        }

        @Test
        @DisplayName("Given valid JSON object with intents array wrapper, when loaded, then parses correctly")
        void loads_intents_from_wrapped_json_object() {
            String json = """
                    {
                      "intents": [
                        {
                          "id": "wrapped.intent",
                          "description": "Wrapped test intent",
                          "examples": ["example one"]
                        }
                      ]
                    }
                    """;

            List<IntentDefinition> intents = loader.loadFromJson(json);

            assertThat(intents).hasSize(1);
            assertThat(intents.get(0).getId()).isEqualTo("wrapped.intent");
        }

        @Test
        @DisplayName("Given default classpath location, when loadFromLocation called, then loads standard intents")
        void loads_standard_intents_from_classpath_resource() {
            List<IntentDefinition> intents = loader.loadFromLocation(DynamicIntentLoader.DEFAULT_INTENT_RESOURCE);

            assertThat(intents).isNotEmpty();
            assertThat(intents)
                    .extracting(IntentDefinition::getId)
                    .contains("general.conversation", "catalog.product.search", "commerce.order.place");
        }
    }

    // =========================================================================
    // 2. Invalid input — null, empty, malformed JSON
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("Given null or blank JSON string, when loaded, then returns empty list")
        void returns_empty_list_for_null_or_blank_json() {
            assertThat(loader.loadFromJson(null)).isEmpty();
            assertThat(loader.loadFromJson("")).isEmpty();
            assertThat(loader.loadFromJson("   ")).isEmpty();
        }

        @Test
        @DisplayName("Given malformed JSON string, when loaded, then recovers gracefully with empty list")
        void returns_empty_list_for_malformed_json() {
            String malformed = "{ this is not valid json }";

            List<IntentDefinition> result = loader.loadFromJson(malformed);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Given null input stream, when loadFromInputStream called, then returns empty list")
        void returns_empty_list_for_null_input_stream() {
            assertThat(loader.loadFromInputStream(null)).isEmpty();
        }
    }

    // =========================================================================
    // 3. Edge cases — non-existent resource fallback, static loader, reload
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given non-existent location, when loadFromLocation called, then falls back to default classpath intents")
        void falls_back_to_default_on_non_existent_location() {
            List<IntentDefinition> intents = loader.loadFromLocation("classpath:non-existent-intents.json");

            assertThat(intents).isNotEmpty();
            assertThat(intents)
                    .extracting(IntentDefinition::getId)
                    .contains("general.conversation");
        }

        @Test
        @DisplayName("Given null resource, when loadFromResource called, then falls back to default intents")
        void falls_back_to_default_on_null_resource() {
            List<IntentDefinition> intents = loader.loadFromResource(null);

            assertThat(intents).isNotEmpty();
            assertThat(intents)
                    .extracting(IntentDefinition::getId)
                    .contains("general.conversation");
        }

        @Test
        @DisplayName("Given static loadDefaultIntents called, then returns populated list from classpath")
        void static_load_default_intents_returns_classpath_intents() {
            List<IntentDefinition> intents = DynamicIntentLoader.loadDefaultIntents();

            assertThat(intents).isNotEmpty();
            assertThat(intents)
                    .extracting(IntentDefinition::getId)
                    .contains("general.conversation", "catalog.product.search");
        }
    }
}
