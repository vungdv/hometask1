package vn.danang.polaris.assistant.intent;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IntentClassification(
        @JsonProperty("intent_id") String intentId,
        @JsonProperty("confidence") double confidence
) {
    public static final String GENERAL_CONVERSATION = "general.conversation";
    public static final String CATALOG_SEARCH = "catalog.product.search";
    public static final String CATALOG_LOOKUP = "catalog.product.lookup";
    public static final String ORDER_STATUS = "information.lookup.order.status";
    public static final String ORDER_DETAILS = "information.lookup.order.details";
    public static final String ORDER_HISTORY = "information.lookup.order.history";
    public static final String ORDER_PLACE = "commerce.order.place";
    public static final String ORDER_CANCEL = "commerce.order.cancel";
}
