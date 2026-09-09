package vn.danang.polaris.repository;

import org.springframework.data.jpa.domain.Specification;

import vn.danang.polaris.entity.Order;
import vn.danang.polaris.entity.OrderStatus;

public final class OrderSpecifications {

    private OrderSpecifications() {}

    public static Specification<Order> hasCustomerId(Long customerId) {
        return (root, cq, cb) -> {
            if (customerId == null) {
                return cb.conjunction();
            }
            return cb.equal(root.get("customer").get("id"), customerId);
        };
    }

    public static Specification<Order> hasStatus(OrderStatus status) {
        return (root, cq, cb) -> {
            if (status == null) {
                return cb.conjunction();
            }
            return cb.equal(root.get("status"), status);
        };
    }
}
