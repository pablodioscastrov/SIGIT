package com.inventario.multisucursal.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * Rotación de inventario y productos de alta/baja demanda (BR-040, RF-032).
 *
 * @param turnoverRatio en cada entrada: unidades vendidas en la ventana ÷
 *        stock actual — aproximación deliberada (ver BR-040), {@code null}
 *        cuando el stock actual es 0 (no calculable, nunca infinito).
 */
public record InventoryDemandResponse(
        String branchId,
        String branchName,
        String windowFrom,
        String windowTo,
        List<ProductDemandEntry> topDemand,
        List<ProductDemandEntry> lowDemand) {

    public record ProductDemandEntry(
            String productId, String sku, String name, BigDecimal unitsSold, BigDecimal currentStock, BigDecimal turnoverRatio) {
    }
}
