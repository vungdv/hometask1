package vn.danang.polaris.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.danang.polaris.domain.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {}