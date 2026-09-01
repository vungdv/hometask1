package vn.danang.polaris.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.danang.polaris.domain.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNumber(String orderNumber);
}