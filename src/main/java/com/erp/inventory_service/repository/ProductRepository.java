package com.erp.inventory_service.repository;

import com.erp.inventory_service.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySkuAndActiveTrue(String sku);

    Page<Product> findByActiveTrue(Pageable pageable);

    Page<Product> findByNameContainingIgnoreCaseAndActiveTrue(
            String name, Pageable pageable);

    boolean existsBySku(String sku);

    @Query("SELECT p FROM Product p WHERE p.active = true AND p.stockQty <= :threshold")
    Page<Product> findLowStockProducts(int threshold, Pageable pageable);
}
