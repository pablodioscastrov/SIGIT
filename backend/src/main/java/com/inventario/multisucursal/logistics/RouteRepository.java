package com.inventario.multisucursal.logistics;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RouteRepository extends JpaRepository<Route, Long> {

    /** El par origen-destino es la identidad de negocio de la ruta (UNIQUE en V24). */
    Optional<Route> findByOriginBranchIdAndDestinationBranchId(Long originBranchId, Long destinationBranchId);

    @Query("""
            SELECT r FROM Route r
             WHERE (:branchId IS NULL OR r.originBranchId = :branchId OR r.destinationBranchId = :branchId)
               AND (:classification IS NULL OR r.classification = :classification)
            """)
    Page<Route> search(
            @Param("branchId") Long branchId,
            @Param("classification") RouteClassification classification,
            Pageable pageable);
}
