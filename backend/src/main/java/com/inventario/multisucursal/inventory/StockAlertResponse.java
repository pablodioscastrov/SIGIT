package com.inventario.multisucursal.inventory;

import java.math.BigDecimal;
import java.time.Instant;

/** `GET /stock-alerts` (docs/API_DESIGN.md, sección 7). */
public record StockAlertResponse(
        String id,
        String branchId,
        String productId,
        String sku,
        String name,
        BigDecimal quantityOnHand,
        BigDecimal minimumStock,
        String status,
        Instant triggeredAt,
        Instant resolvedAt) {

    public static StockAlertResponse from(StockAlertRow row) {
        return new StockAlertResponse(
                String.valueOf(row.id()),
                String.valueOf(row.branchId()),
                String.valueOf(row.productId()),
                row.sku(),
                row.name(),
                row.quantityOnHand(),
                row.minimumStock(),
                row.status().name(),
                row.triggeredAt(),
                row.resolvedAt());
    }
}
