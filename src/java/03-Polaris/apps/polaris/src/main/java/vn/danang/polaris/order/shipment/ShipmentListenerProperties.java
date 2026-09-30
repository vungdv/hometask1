package vn.danang.polaris.order.shipment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import vn.danang.polaris.events.fulfilment.FulfilmentEvents;

/** Order's consumer of the Fulfilment shipments topic (F3). Fulfilment owns the topic; Order only reads it. */
@ConfigurationProperties("polaris.order.shipment-listener")
public record ShipmentListenerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue(FulfilmentEvents.DESTINATION) String topic,
        @DefaultValue("order.shipments") String groupId,
        /** Retries after the first failure, before the record is logged at ERROR and skipped. */
        @DefaultValue("3") int retries,
        @DefaultValue("500ms") java.time.Duration backoffInitial) {
}
