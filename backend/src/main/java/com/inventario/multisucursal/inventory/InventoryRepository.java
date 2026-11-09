package com.inventario.multisucursal.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductIdAndBranchId(Long productId, Long branchId);

    /**
     * Bloqueo optimista con reintento (docs/CRITICAL_FLOWS.md, sección 1.2;
     * BR-022): actualización atómica condicionada a que {@code version} no
     * haya cambiado desde la lectura. {@code clearAutomatically = true} vacía
     * el contexto de persistencia tras el UPDATE para que el siguiente
     * intento de {@link #findByProductIdAndBranchId} dentro del mismo
     * reintento relea el estado real de la base de datos, no una copia en
     * caché de primer nivel ya obsoleta.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Inventory i
               SET i.quantityOnHand = :newQuantity, i.version = i.version + 1, i.updatedAt = :now
             WHERE i.id = :id AND i.version = :expectedVersion
            """)
    int applyQuantity(
            @Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("newQuantity") BigDecimal newQuantity,
            @Param("now") Instant now);

    /**
     * Variante de {@link #applyQuantity} para la recepción de compra
     * (flujo B; BR-004, BR-016): actualiza cantidad y costo promedio
     * ponderado de forma atómica — nunca puede persistirse el cambio de
     * stock sin el recálculo de costo, ni viceversa (BR-016).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Inventory i
               SET i.quantityOnHand = :newQuantity, i.averageUnitCost = :newAverageUnitCost,
                   i.version = i.version + 1, i.updatedAt = :now
             WHERE i.id = :id AND i.version = :expectedVersion
            """)
    int applyReceipt(
            @Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("newQuantity") BigDecimal newQuantity,
            @Param("newAverageUnitCost") BigDecimal newAverageUnitCost,
            @Param("now") Instant now);

    @Query("""
            SELECT i FROM Inventory i, Product p
             WHERE i.productId = p.id
               AND (:branchId IS NULL OR i.branchId = :branchId)
               AND (:productId IS NULL OR i.productId = :productId)
               AND (:search IS NULL
                        OR LOWER(p.sku) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                        OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
               AND (:lowStock = FALSE OR i.quantityOnHand <= i.minimumStock)
            """)
    Page<Inventory> search(
            @Param("branchId") Long branchId,
            @Param("productId") Long productId,
            @Param("search") String search,
            @Param("lowStock") boolean lowStock,
            Pageable pageable);

    /**
     * Catálogo de productos que la sucursal tiene registrados en inventario
     * (BR-040, dashboard RF-032) — es el ancla para completar con 0 las
     * unidades vendidas de un producto sin ventas en la ventana, sin asumir
     * que "sin ventas" signifique "no existe en esta sucursal".
     */
    @Query("SELECT i.productId FROM Inventory i WHERE i.branchId = :branchId")
    List<Long> productIdsInBranch(@Param("branchId") Long branchId);

    List<Inventory> findByBranchIdAndProductIdIn(Long branchId, Collection<Long> productIds);

    /** Conteo agregado para el indicador de reabastecimiento (BR-042, dashboard RF-034). */
    @Query("SELECT COUNT(i) FROM Inventory i WHERE i.branchId = :branchId AND i.quantityOnHand <= i.minimumStock")
    long countLowStock(@Param("branchId") Long branchId);

    /**
     * Los más urgentes primero: más por debajo de su mínimo, desempatado por
     * el que tiene menos stock absoluto (BR-042). {@code LIMIT} vía
     * {@code Pageable} — nunca se trae todo el inventario de la sucursal
     * para ordenar en memoria.
     */
    @Query("""
            SELECT i FROM Inventory i
             WHERE i.branchId = :branchId AND i.quantityOnHand <= i.minimumStock
             ORDER BY (i.quantityOnHand - i.minimumStock) ASC, i.quantityOnHand ASC
            """)
    List<Inventory> findMostUrgentLowStock(@Param("branchId") Long branchId, Pageable pageable);
}
