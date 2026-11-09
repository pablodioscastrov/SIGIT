package com.inventario.multisucursal.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    Optional<InventoryMovement> findByIdempotencyKey(String idempotencyKey);

    /**
     * {@code dateFrom}/{@code dateTo} deben llegar siempre con un valor
     * (nunca {@code null}) — {@link InventoryMovementService#list} resuelve
     * límites amplios por defecto cuando el filtro no viene en la petición.
     * PostgreSQL no logra inferir el tipo de un parámetro {@code Instant}
     * nulo dentro de una comparación ({@code could not determine data type
     * of parameter} / lo intenta como {@code bytea}), a diferencia de
     * {@code branchId}/{@code productId}/{@code reason} (Long/enum), que sí
     * toleran el patrón {@code :param IS NULL OR ...} sin problema —
     * descubierto en verificación en vivo contra Postgres real (H2 no lo
     * reproduce).
     */
    @Query("""
            SELECT m FROM InventoryMovement m
             WHERE (:branchId IS NULL OR m.branchId = :branchId)
               AND (:productId IS NULL OR m.productId = :productId)
               AND (:reason IS NULL OR m.reason = :reason)
               AND m.occurredAt >= :dateFrom
               AND m.occurredAt <= :dateTo
            """)
    Page<InventoryMovement> search(
            @Param("branchId") Long branchId,
            @Param("productId") Long productId,
            @Param("reason") MovementReason reason,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            Pageable pageable);
}
