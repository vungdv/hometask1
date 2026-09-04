package vn.danang.polaris.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.danang.polaris.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNumber(String orderNumber);
}