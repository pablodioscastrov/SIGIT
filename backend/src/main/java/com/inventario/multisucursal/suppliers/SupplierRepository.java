package com.inventario.multisucursal.suppliers;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    boolean existsByTaxId(String taxId);

    @Query("""
            SELECT s FROM Supplier s
            WHERE (:search IS NULL
                     OR LOWER(s.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                     OR LOWER(s.taxId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
              AND (:active IS NULL OR s.active = :active)
            """)
    Page<Supplier> search(@Param("search") String search, @Param("active") Boolean active, Pageable pageable);
}
