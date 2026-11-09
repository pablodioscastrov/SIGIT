package com.inventario.multisucursal.inventory;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Proyección intermedia de la consulta agregada (tipos nativos, no `String`)
 * — mismo patrón que {@code sales.SalesAggregate}/{@code ProductDemand}: la
 * conversión a identificadores `String` de la API ocurre en
 * {@link StockAlertResponse#from}, no aquí.
 */
public record StockAlertRow(
        Long id,
        Long branchId,
        Long productId,
        String sku,
        String name,
        BigDecimal quantityOnHand,
        BigDecimal minimumStock,
        StockAlertStatus status,
        Instant triggeredAt,
        Instant resolvedAt) {
}
