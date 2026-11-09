package com.inventario.multisucursal.products;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    @Query("""
            SELECT p FROM Product p
            WHERE (:search IS NULL
                     OR LOWER(p.sku) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                     OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
              AND (:active IS NULL OR p.active = :active)
            """)
    Page<Product> search(@Param("search") String search, @Param("active") Boolean active, Pageable pageable);
}
