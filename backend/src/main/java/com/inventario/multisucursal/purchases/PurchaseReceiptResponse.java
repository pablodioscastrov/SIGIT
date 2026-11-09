package com.inventario.multisucursal.purchases;

import java.math.BigDecimal;
import java.util.List;

/** docs/openapi.yaml, PurchaseReceiptResponse. */
public record PurchaseReceiptResponse(
        String purchaseOrderId,
        PurchaseOrderStatus status,
        List<ReceivedItem> items,
        List<InventoryUpdate> inventoryUpdates) {

    public record ReceivedItem(String purchaseOrderItemId, BigDecimal quantityOrdered, BigDecimal quantityReceived, BigDecimal pending) {
    }

    public record InventoryUpdate(String productId, String branchId, BigDecimal quantityOnHand, BigDecimal averageUnitCost) {
    }
}
