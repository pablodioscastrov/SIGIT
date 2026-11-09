package com.inventario.multisucursal.dashboard;

import java.math.BigDecimal;
import java.util.List;

/** Productos próximos a agotarse (BR-042, RF-034). */
public record ReplenishmentResponse(
        String branchId,
        String branchName,
        long lowStockCount,
        List<ReplenishmentEntry> mostUrgent) {

    public record ReplenishmentEntry(String productId, String sku, String name, BigDecimal quantityOnHand, BigDecimal minimumStock) {
    }
}
