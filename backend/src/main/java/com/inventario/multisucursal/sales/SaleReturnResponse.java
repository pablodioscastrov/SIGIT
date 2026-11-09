package com.inventario.multisucursal.sales;

import java.math.BigDecimal;
import java.util.List;

/** BR-052. Mismo esquema de respuesta que {@code PurchaseReceiptResponse} — otra operación post-hoc por línea contra un documento ya confirmado. */
public record SaleReturnResponse(String saleId, List<ReturnedItem> items, List<InventoryUpdate> inventoryUpdates) {

    public record ReturnedItem(String saleItemId, BigDecimal quantity, BigDecimal quantityReturned, BigDecimal pending) {
    }

    /** Sin {@code averageUnitCost}: una devolución repone cantidad al costo promedio ya vigente, nunca lo recalcula (a diferencia de una recepción de compra). */
    public record InventoryUpdate(String productId, String branchId, BigDecimal quantityOnHand) {
    }
}
