// domain/OrderStatus.java
package vn.danang.polaris.domain;

public enum OrderStatus {
    PLACED, CONFIRMED, PARCELED, DELIVERING, DELIVERED, CANCELLED;

    public boolean isCancellable() {
        return this == PLACED || this == CONFIRMED;
    }
}