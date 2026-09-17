package vn.danang.polaris.assistant.intent;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "polaris.assistant.intent")
public class IntentTaxonomyProperties {

    private List<IntentDefinition> intents = new ArrayList<>();

    public IntentTaxonomyProperties() {
        this.intents = createDefaultIntents();
    }

    public List<IntentDefinition> getIntents() {
        return intents;
    }

    public void setIntents(List<IntentDefinition> intents) {
        if (intents != null && !intents.isEmpty()) {
            this.intents = intents;
        }
    }

    public static List<IntentDefinition> createDefaultIntents() {
        List<IntentDefinition> list = new ArrayList<>();

        list.add(new IntentDefinition(
                IntentClassification.GENERAL_CONVERSATION,
                "User engages in greetings, small talk, or general inquiries unrelated to catalog or orders",
                List.of("hello", "hi there", "who are you", "help me", "how are you", "good morning"),
                List.of(),
                null,
                0.50,
                false
        ));

        list.add(new IntentDefinition(
                IntentClassification.CATALOG_SEARCH,
                "User wants to browse or search for products by keyword, category, or price range",
                List.of("show me running shoes under $100", "what electronics do you have in stock", "find chargers", "search products", "browse items"),
                List.of("search_available_products", "search_products", "search_promotions"),
                "catalog.read",
                0.80,
                false
        ));

        list.add(new IntentDefinition(
                IntentClassification.CATALOG_LOOKUP,
                "User wants details on a specific, already-identified product by SKU",
                List.of("tell me more about SM-PH-001", "is PROD-001 in stock", "check sku NG-CHARGER-02", "product details for SM-PH-001"),
                List.of("get_product_by_sku"),
                "catalog.read",
                0.85,
                false
        ));

        list.add(new IntentDefinition(
                IntentClassification.ORDER_STATUS,
                "User wants a quick status check on a single known order — not full details",
                List.of("where is my order ORD-1001", "status of order 1234", "track order ORD-55", "check status of my order"),
                List.of("get_order_status"),
                "order.read",
                0.85,
                false
        ));

        list.add(new IntentDefinition(
                IntentClassification.ORDER_DETAILS,
                "User wants full order contents — line items, pricing, fulfillment — not just status",
                List.of("what did I order in ORD-1001", "show me the full details of my last order", "view details for order ORD-999", "order contents for ORD-1001"),
                List.of("get_order_details"),
                "order.read",
                0.85,
                false
        ));

        list.add(new IntentDefinition(
                IntentClassification.ORDER_HISTORY,
                "User wants to see multiple past orders, optionally filtered by status",
                List.of("show my order history", "list my cancelled orders", "show all my previous purchases", "what are my past orders"),
                List.of("list_customer_orders"),
                "order.read",
                0.80,
                false
        ));

        list.add(new IntentDefinition(
                IntentClassification.CUSTOMER_LOOKUP,
                "User wants to find, look up, or identify a customer by name",
                List.of("find customer Alice", "look up customer Tran", "search customer named Bob", "who is customer Chi", "I am Alice Tran", "customer lookup for Alice"),
                List.of("search_customers_by_name"),
                "order.read",
                0.85,
                false
        ));

        list.add(new IntentDefinition(
                IntentClassification.ORDER_PLACE,
                "User wants to place a new order for one or more items",
                List.of("order 2 of NG-EARBUD-01", "buy the wireless earbuds", "place order for item PROD-1", "I want to buy this", "order 2 of NG-EARBUD-01 for Alice", "place order for Alice Tran"),
                List.of("place_order", "search_customers_by_name"),
                "order.write",
                0.92,
                true
        ));

        list.add(new IntentDefinition(
                IntentClassification.ORDER_CANCEL,
                "User wants to cancel an existing order",
                List.of("cancel my order ORD-1001", "I want to cancel that last order", "please cancel order 1234"),
                List.of("cancel_order"),
                "order.write",
                0.92,
                true
        ));

        return list;
    }
}
