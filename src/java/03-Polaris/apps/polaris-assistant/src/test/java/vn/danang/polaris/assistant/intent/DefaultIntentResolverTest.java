package vn.danang.polaris.assistant.intent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultIntentResolverTest {

    private DefaultIntentResolver resolver;

    @BeforeEach
    void setUp() {
        IntentTaxonomyProperties properties = new IntentTaxonomyProperties();
        resolver = new DefaultIntentResolver(properties);
    }

    // =========================================================================
    // 1. Happy path — main successful flows
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @ParameterizedTest(name = "Query \"{0}\" resolves to CATALOG_SEARCH")
        @ValueSource(strings = {
                "show me running shoes under $100",
                "find chargers in stock",
                "browse electronics catalog",
                "search for wireless headphones"
        })
        @DisplayName("Given catalog search queries, when resolved, then classifies as CATALOG_SEARCH with high confidence")
        void resolves_catalog_search_for_product_search_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.CATALOG_SEARCH);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.80);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to CATALOG_LOOKUP")
        @ValueSource(strings = {
                "tell me more about SM-PH-001",
                "is SKU NG-CHARGER-02 in stock",
                "product details for AP-MACBOOK-16"
        })
        @DisplayName("Given specific SKU mentions, when resolved, then classifies as CATALOG_LOOKUP with high confidence")
        void resolves_catalog_lookup_for_sku_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.CATALOG_LOOKUP);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_STATUS")
        @ValueSource(strings = {
                "where is my order ORD-1001",
                "status of order 1234",
                "track order ORD-5555"
        })
        @DisplayName("Given order tracking queries, when resolved, then classifies as ORDER_STATUS")
        void resolves_order_status_for_tracking_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.ORDER_STATUS);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_DETAILS")
        @ValueSource(strings = {
                "what did I order in ORD-1001",
                "show me the full details of my last order",
                "order contents for ORD-1001"
        })
        @DisplayName("Given order breakdown queries, when resolved, then classifies as ORDER_DETAILS")
        void resolves_order_details_for_item_breakdown_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.ORDER_DETAILS);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_HISTORY")
        @ValueSource(strings = {
                "show my order history",
                "list my cancelled orders",
                "previous purchases"
        })
        @DisplayName("Given order history queries, when resolved, then classifies as ORDER_HISTORY")
        void resolves_order_history_for_past_purchase_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.ORDER_HISTORY);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.80);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_PLACE")
        @ValueSource(strings = {
                "order 2 of NG-EARBUD-01",
                "buy the wireless earbuds",
                "place order for Alice Tran",
                "order 2 of NG-EARBUD-01 for Alice"
        })
        @DisplayName("Given purchasing queries, when resolved, then classifies as ORDER_PLACE")
        void resolves_order_place_for_purchase_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.ORDER_PLACE);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.92);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to ORDER_CANCEL")
        @ValueSource(strings = {
                "cancel my order ORD-1001",
                "I want to cancel that last order",
                "abort order ORD-999"
        })
        @DisplayName("Given cancellation queries, when resolved, then classifies as ORDER_CANCEL")
        void resolves_order_cancel_for_cancellation_requests(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.ORDER_CANCEL);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.92);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to CUSTOMER_LOOKUP")
        @ValueSource(strings = {
                "find customer Alice",
                "who is customer Chi",
                "my name is Alice Tran"
        })
        @DisplayName("Given customer identification queries, when resolved, then classifies as CUSTOMER_LOOKUP")
        void resolves_customer_lookup_for_customer_queries(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.CUSTOMER_LOOKUP);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        }

        @ParameterizedTest(name = "Query \"{0}\" resolves to GENERAL_CONVERSATION")
        @ValueSource(strings = {
                "hello",
                "hi there",
                "who are you?",
                "what can you do"
        })
        @DisplayName("Given conversational greetings and help queries, when resolved, then classifies as GENERAL_CONVERSATION")
        void resolves_general_conversation_for_greetings(String query) {
            IntentClassification result = resolver.resolve(query, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        }
    }

    // =========================================================================
    // 2. Invalid input — common validation and boundary cases
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @ParameterizedTest(name = "Blank query \"{0}\" resolves to GENERAL_CONVERSATION")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n"})
        @DisplayName("Given null, empty, or whitespace-only queries, when resolved, then returns GENERAL_CONVERSATION with full confidence")
        void resolves_null_empty_or_whitespace_as_general_conversation(String blankQuery) {
            IntentClassification result = resolver.resolve(blankQuery, List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
            assertThat(result.confidence()).isEqualTo(1.0);
        }
    }

    // =========================================================================
    // 3. Edge cases — boundaries, order vs SKU disambiguation, unknown queries
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given order identifier ORD-1001, when resolving inquiry, then does not classify as CATALOG_LOOKUP")
        void does_not_treat_order_number_as_catalog_lookup() {
            IntentClassification result = resolver.resolve("sku ORD-1001", List.of());

            assertThat(result.intentId()).isNotEqualTo(IntentClassification.CATALOG_LOOKUP);
        }

        @Test
        @DisplayName("Given completely unknown query, when resolved, then falls back to GENERAL_CONVERSATION with low confidence")
        void falls_back_to_general_conversation_with_low_confidence_for_unknown_input() {
            IntentClassification result = resolver.resolve("xyzzy qwerty foobar 98765", List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
            assertThat(result.confidence()).isLessThan(0.50);
        }

        @Test
        @DisplayName("Given mixed case greeting query, when resolved, then correctly classifies as GENERAL_CONVERSATION")
        void handles_case_insensitive_greetings() {
            IntentClassification result = resolver.resolve("hElLo ThErE", List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        }

        @Test
        @DisplayName("Given default constructor without explicit properties, when instantiated, then resolves intents correctly")
        void operates_correctly_with_default_constructor() {
            DefaultIntentResolver defaultResolver = new DefaultIntentResolver();
            IntentClassification result = defaultResolver.resolve("hello", List.of());

            assertThat(result.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
        }
    }
}
