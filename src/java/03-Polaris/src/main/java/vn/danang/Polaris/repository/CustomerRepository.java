package vn.danang.polaris.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.danang.polaris.domain.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByEmail(String email);
}