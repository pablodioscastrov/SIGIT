package com.inventario.multisucursal.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface SaleItemRepository extends JpaRepository<SaleItem, Long> {

    List<SaleItem> findBySaleId(Long saleId);

    /** Bloqueo optimista con reintento sobre la línea (BR-052) — mismo patrón que {@code PurchaseOrderItemRepository.applyReceipt}. */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE SaleItem i
               SET i.quantityReturned = :newQuantityReturned, i.version = i.version + 1
             WHERE i.id = :id AND i.version = :expectedVersion
            """)
    int applyReturn(
            @Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("newQuantityReturned") BigDecimal newQuantityReturned);

    /**
     * Unidades vendidas por producto en una ventana de tiempo, para una sola
     * sucursal (BR-040, dashboard RF-032). {@code SaleItem} no tiene
     * {@code branchId}/{@code saleDate} propios — se correlaciona con
     * {@code Sale} mediante una unión sin asociación JPA (mismo patrón ya
     * usado en {@code InventoryRepository.search} con {@code Product}), no
     * una consulta nativa. Solo devuelve productos con **al menos una venta**
     * en la ventana — los de cero ventas se completan en el servicio a partir
     * del catálogo de inventario de la sucursal, que es quien sabe qué
     * productos existen ahí.
     */
    @Query("""
            SELECT new com.inventario.multisucursal.sales.ProductDemand(si.productId, SUM(si.quantity))
              FROM SaleItem si, Sale s
             WHERE si.saleId = s.id
               AND s.status = com.inventario.multisucursal.sales.SaleStatus.CONFIRMED
               AND s.branchId = :branchId
               AND s.saleDate >= :from AND s.saleDate < :to
             GROUP BY si.productId
            """)
    List<ProductDemand> demandByProduct(@Param("branchId") Long branchId, @Param("from") Instant from, @Param("to") Instant to);
}
