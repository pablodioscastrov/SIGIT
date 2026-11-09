package com.inventario.multisucursal.sales;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    Optional<Sale> findByClientReferenceId(String clientReferenceId);

    /** {@code dateFrom}/{@code dateTo} nunca nulos al llegar aquí — mismo motivo que InventoryMovementRepository.search. */
    @Query("""
            SELECT s FROM Sale s
             WHERE (:branchId IS NULL OR s.branchId = :branchId)
               AND (:status IS NULL OR s.status = :status)
               AND s.saleDate >= :dateFrom
               AND s.saleDate <= :dateTo
            """)
    Page<Sale> search(
            @Param("branchId") Long branchId,
            @Param("status") SaleStatus status,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            Pageable pageable);

    /**
     * Base del indicador "ventas del mes vs. anteriores" (BR-039, dashboard
     * RF-031). Un rango sin ventas confirmadas devuelve {@code totalSales = 0}
     * gracias al {@code COALESCE} — nunca una fila ausente que el servicio
     * tendría que distinguir de "hubo un error".
     */
    @Query("""
            SELECT new com.inventario.multisucursal.sales.SalesAggregate(COALESCE(SUM(s.total), 0), COUNT(s))
              FROM Sale s
             WHERE s.branchId = :branchId
               AND s.status = com.inventario.multisucursal.sales.SaleStatus.CONFIRMED
               AND s.saleDate >= :from AND s.saleDate < :to
            """)
    SalesAggregate aggregateForRange(@Param("branchId") Long branchId, @Param("from") Instant from, @Param("to") Instant to);
}
