package com.inventario.multisucursal.purchases;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    boolean existsByOrderNumber(String orderNumber);

    @Query("""
            SELECT o FROM PurchaseOrder o
             WHERE (:branchId IS NULL OR o.branchId = :branchId)
               AND (:supplierId IS NULL OR o.supplierId = :supplierId)
               AND (:status IS NULL OR o.status = :status)
            """)
    Page<PurchaseOrder> search(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId,
            @Param("status") PurchaseOrderStatus status,
            Pageable pageable);
}
