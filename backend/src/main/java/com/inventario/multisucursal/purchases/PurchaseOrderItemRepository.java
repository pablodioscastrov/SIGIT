package com.inventario.multisucursal.purchases;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByPurchaseOrderId(Long purchaseOrderId);

    /**
     * Bloqueo optimista con reintento sobre la línea (docs/CRITICAL_FLOWS.md,
     * flujo B; decisión pendiente #4 de docs/BUSINESS_RULES.md, ahora
     * resuelta) — mismo patrón que {@code InventoryRepository.applyQuantity}.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE PurchaseOrderItem i
               SET i.quantityReceived = :newQuantityReceived, i.version = i.version + 1
             WHERE i.id = :id AND i.version = :expectedVersion
            """)
    int applyReceipt(
            @Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("newQuantityReceived") BigDecimal newQuantityReceived);
}
