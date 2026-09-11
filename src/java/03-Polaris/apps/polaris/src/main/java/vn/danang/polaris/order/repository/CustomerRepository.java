package vn.danang.polaris.order.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.danang.polaris.order.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByEmail(String email);
}