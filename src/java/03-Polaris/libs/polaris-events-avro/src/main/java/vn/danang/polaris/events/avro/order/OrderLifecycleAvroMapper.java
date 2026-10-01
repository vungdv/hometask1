package vn.danang.polaris.events.avro.order;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import vn.danang.polaris.events.order.OrderLifecycleEvent;
import vn.danang.polaris.events.order.OrderMilestone;

/**
 * Maps the published JSON contract record to its generated Avro twin and back. This is the cost of running two
 * representations side by side: every field is mapped by hand, and the mapping is the place drift would hide.
 */
public final class OrderLifecycleAvroMapper {

    private OrderLifecycleAvroMapper() {
    }

    public static vn.danang.polaris.events.avro.order.OrderLifecycleEvent toAvro(OrderLifecycleEvent e) {
        var customer = e.customer() == null ? null
                : Customer.newBuilder().setId(e.customer().id()).setName(e.customer().name())
                        .setEmail(e.customer().email()).build();
        List<Item> items = e.items().stream()
                .map(i -> Item.newBuilder().setSku(i.sku()).setName(i.name()).setQuantity(i.quantity())
                        .setUnitPrice(i.unitPrice()).build())
                .toList();
        return vn.danang.polaris.events.avro.order.OrderLifecycleEvent.newBuilder()
                .setOrderNumber(e.orderNumber())
                .setStatus(vn.danang.polaris.events.avro.order.OrderMilestone.valueOf(e.status().name()))
                // Avro timestamp-millis: sub-millisecond precision of the Instant is dropped
                .setOccurredAt(e.occurredAt().truncatedTo(ChronoUnit.MILLIS))
                .setCustomer(customer)
                .setItems(items)
                .setTotalAmount(e.totalAmount())
                .setCurrency(e.currency())
                .setAssignedPartner(e.assignedPartner())
                .build();
    }

    public static OrderLifecycleEvent fromAvro(vn.danang.polaris.events.avro.order.OrderLifecycleEvent a) {
        OrderLifecycleEvent.Customer customer = a.getCustomer() == null ? null
                : new OrderLifecycleEvent.Customer(a.getCustomer().getId(), a.getCustomer().getName(),
                        a.getCustomer().getEmail());
        Instant at = a.getOccurredAt();
        return new OrderLifecycleEvent(a.getOrderNumber(), OrderMilestone.valueOf(a.getStatus().name()), at, customer,
                a.getItems().stream()
                        .map(i -> new OrderLifecycleEvent.Item(i.getSku(), i.getName(), i.getQuantity(),
                                i.getUnitPrice()))
                        .toList(),
                a.getTotalAmount(), a.getCurrency(), a.getAssignedPartner());
    }
}
