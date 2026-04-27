package com.erp.inventory_service.repository;

import com.erp.inventory_service.model.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer,Long> {
    Optional<Customer> findByEmailAndActiveTrue(String email);

    boolean existsByEmail(String email);

    Page<Customer> findByActiveTrue(Pageable pageable);

    Page<Customer> findByNameContainingIgnoreCaseAndActiveTrue(
            String name, Pageable pageable);
}
