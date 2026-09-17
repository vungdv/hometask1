package vn.danang.polaris.assistant.intent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultIntentResolverTest {

    private DefaultIntentResolver resolver;

    @BeforeEach
    void setUp() {
        IntentTaxonomyProperties properties = new IntentTaxonomyProperties();
        resolver = new DefaultIntentResolver(properties);
    }

    @Test
    @DisplayName("Should resolve catalog search intent for product queries")
    void resolve_catalogSearchQueries() {
        IntentClassification res1 = resolver.resolve("show me running shoes under $100", List.of());
        assertThat(res1.intentId()).isEqualTo(IntentClassification.CATALOG_SEARCH);
        assertThat(res1.confidence()).isGreaterThanOrEqualTo(0.80);

        IntentClassification res2 = resolver.resolve("find chargers in stock", List.of());
        assertThat(res2.intentId()).isEqualTo(IntentClassification.CATALOG_SEARCH);
        assertThat(res2.confidence()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    @DisplayName("Should resolve product lookup intent for specific SKU mentions")
    void resolve_catalogLookupQueries() {
        IntentClassification res = resolver.resolve("tell me more about SM-PH-001", List.of());
        assertThat(res.intentId()).isEqualTo(IntentClassification.CATALOG_LOOKUP);
        assertThat(res.confidence()).isGreaterThanOrEqualTo(0.85);

        IntentClassification res2 = resolver.resolve("is SKU NG-CHARGER-02 in stock", List.of());
        assertThat(res2.intentId()).isEqualTo(IntentClassification.CATALOG_LOOKUP);
        assertThat(res2.confidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    @DisplayName("Should resolve order status intent for status checks")
    void resolve_orderStatusQueries() {
        IntentClassification res = resolver.resolve("where is my order ORD-1001", List.of());
        assertThat(res.intentId()).isEqualTo(IntentClassification.ORDER_STATUS);
        assertThat(res.confidence()).isGreaterThanOrEqualTo(0.85);

        IntentClassification res2 = resolver.resolve("status of order 1234", List.of());
        assertThat(res2.intentId()).isEqualTo(IntentClassification.ORDER_STATUS);
        assertThat(res2.confidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    @DisplayName("Should resolve order details intent for item and breakdown queries")
    void resolve_orderDetailsQueries() {
        IntentClassification res = resolver.resolve("what did I order in ORD-1001", List.of());
        assertThat(res.intentId()).isEqualTo(IntentClassification.ORDER_DETAILS);
        assertThat(res.confidence()).isGreaterThanOrEqualTo(0.85);

        IntentClassification res2 = resolver.resolve("show me the full details of my last order", List.of());
        assertThat(res2.intentId()).isEqualTo(IntentClassification.ORDER_DETAILS);
        assertThat(res2.confidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    @DisplayName("Should resolve order history intent for past purchase listings")
    void resolve_orderHistoryQueries() {
        IntentClassification res = resolver.resolve("show my order history", List.of());
        assertThat(res.intentId()).isEqualTo(IntentClassification.ORDER_HISTORY);
        assertThat(res.confidence()).isGreaterThanOrEqualTo(0.80);

        IntentClassification res2 = resolver.resolve("list my cancelled orders", List.of());
        assertThat(res2.intentId()).isEqualTo(IntentClassification.ORDER_HISTORY);
        assertThat(res2.confidence()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    @DisplayName("Should resolve place order intent for purchasing queries")
    void resolve_orderPlaceQueries() {
        IntentClassification res = resolver.resolve("order 2 of NG-EARBUD-01", List.of());
        assertThat(res.intentId()).isEqualTo(IntentClassification.ORDER_PLACE);
        assertThat(res.confidence()).isGreaterThanOrEqualTo(0.92);

        IntentClassification res2 = resolver.resolve("buy the wireless earbuds", List.of());
        assertThat(res2.intentId()).isEqualTo(IntentClassification.ORDER_PLACE);
        assertThat(res2.confidence()).isGreaterThanOrEqualTo(0.92);
    }

    @Test
    @DisplayName("Should resolve cancel order intent for cancellation requests")
    void resolve_orderCancelQueries() {
        IntentClassification res = resolver.resolve("cancel my order ORD-1001", List.of());
        assertThat(res.intentId()).isEqualTo(IntentClassification.ORDER_CANCEL);
        assertThat(res.confidence()).isGreaterThanOrEqualTo(0.92);

        IntentClassification res2 = resolver.resolve("I want to cancel that last order", List.of());
        assertThat(res2.intentId()).isEqualTo(IntentClassification.ORDER_CANCEL);
        assertThat(res2.confidence()).isGreaterThanOrEqualTo(0.92);
    }

    @Test
    @DisplayName("Should resolve general conversation intent for greetings and help")
    void resolve_generalConversationQueries() {
        IntentClassification res = resolver.resolve("hello", List.of());
        assertThat(res.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
        assertThat(res.confidence()).isGreaterThanOrEqualTo(0.90);

        IntentClassification res2 = resolver.resolve("who are you?", List.of());
        assertThat(res2.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
        assertThat(res2.confidence()).isGreaterThanOrEqualTo(0.90);

        IntentClassification res3 = resolver.resolve("", List.of());
        assertThat(res3.intentId()).isEqualTo(IntentClassification.GENERAL_CONVERSATION);
        assertThat(res3.confidence()).isEqualTo(1.0);
    }
}
