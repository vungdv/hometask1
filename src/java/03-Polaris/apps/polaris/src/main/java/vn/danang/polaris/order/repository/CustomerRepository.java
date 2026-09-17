package vn.danang.polaris.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.danang.polaris.order.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByEmail(String email);

    /**
     * Fuzzy customer name search using case-insensitive substring matching.
     * Works on both H2 (test) and PostgreSQL (production).
     *
     * For production PostgreSQL at scale, complement with the pg_trgm GIN index
     * and similarity() ordering documented in V9 migration notes.
     */
    @Query("SELECT c FROM Customer c WHERE LOWER(c.fullName) LIKE LOWER(CONCAT('%', :name, '%')) ORDER BY c.fullName ASC")
    List<Customer> searchByNameFuzzy(@Param("name") String name, org.springframework.data.domain.Pageable pageable);
}