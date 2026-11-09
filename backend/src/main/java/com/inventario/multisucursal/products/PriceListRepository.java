package com.inventario.multisucursal.products;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PriceListRepository extends JpaRepository<PriceList, Long> {

    /**
     * No se usa {@code existsByNameAndBranchId} derivado: para una lista
     * global ({@code branchId = null}), Spring Data traduciría el parámetro
     * nulo a {@code branch_id = NULL} (SQL, siempre falso), no a
     * {@code IS NULL} — nunca detectaría el duplicado global.
     */
    @Query("SELECT COUNT(p) > 0 FROM PriceList p WHERE p.name = :name AND ((:branchId IS NULL AND p.branchId IS NULL) OR p.branchId = :branchId)")
    boolean existsByNameAndBranch(@Param("name") String name, @Param("branchId") Long branchId);

    Optional<PriceList> findFirstByBranchIdAndActiveTrue(Long branchId);

    Optional<PriceList> findFirstByBranchIdIsNullAndActiveTrue();

    @Query("""
            SELECT p FROM PriceList p
             WHERE (:branchId IS NULL OR p.branchId = :branchId)
               AND (:active IS NULL OR p.active = :active)
            """)
    Page<PriceList> search(@Param("branchId") Long branchId, @Param("active") Boolean active, Pageable pageable);
}
