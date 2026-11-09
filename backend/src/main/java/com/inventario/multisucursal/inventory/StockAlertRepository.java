package com.inventario.multisucursal.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {

    boolean existsByInventoryIdAndStatus(Long inventoryId, StockAlertStatus status);

    /**
     * Única transición posible (`ACTIVE` → `RESOLVED`, BR-010): `UPDATE`
     * atómico y condicionado, nunca "leer, mutar la entidad y guardar" —
     * mismo motivo que el resto del dominio evita ese patrón (una entidad
     * mutada en memoria después de un `UPDATE` con {@code clearAutomatically
     * = true} en la misma transacción queda `detached` y su cambio se
     * perdería en silencio). Devuelve {@code 0} cuando no había ninguna
     * alerta activa que resolver — el caso normal cuando el stock nunca
     * había bajado del mínimo, no un error.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE StockAlert a
               SET a.status = com.inventario.multisucursal.inventory.StockAlertStatus.RESOLVED, a.resolvedAt = :now
             WHERE a.inventoryId = :inventoryId
               AND a.status = com.inventario.multisucursal.inventory.StockAlertStatus.ACTIVE
            """)
    int resolveActive(@Param("inventoryId") Long inventoryId, @Param("now") Instant now);

    /**
     * Unión sin asociación JPA entre `StockAlert`/`Inventory`/`Product` —
     * mismo patrón de theta-join ya establecido en
     * {@code InventoryRepository.search} (`Inventory, Product`) y
     * {@code SaleItemRepository.demandByProduct` (`SaleItem, Sale`), porque
     * el modelo es deliberadamente plano (sin `@ManyToOne`).
     */
    @Query("""
            SELECT new com.inventario.multisucursal.inventory.StockAlertRow(
                a.id, i.branchId, i.productId, p.sku, p.name, i.quantityOnHand, i.minimumStock,
                a.status, a.triggeredAt, a.resolvedAt)
              FROM StockAlert a, Inventory i, Product p
             WHERE a.inventoryId = i.id AND i.productId = p.id
               AND (:branchId IS NULL OR i.branchId = :branchId)
               AND (:status IS NULL OR a.status = :status)
            """)
    Page<StockAlertRow> search(@Param("branchId") Long branchId, @Param("status") StockAlertStatus status, Pageable pageable);
}
