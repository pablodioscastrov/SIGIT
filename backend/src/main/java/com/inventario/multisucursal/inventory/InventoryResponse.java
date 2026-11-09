package com.inventario.multisucursal.inventory;

import java.math.BigDecimal;
import java.time.Instant;

/** docs/API_DESIGN.md, sección 8: InventoryDTO. */
public record InventoryResponse(
        String id,
        String productId,
        String branchId,
        BigDecimal quantityOnHand,
        BigDecimal averageUnitCost,
        BigDecimal minimumStock,
        Instant updatedAt) {

    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(
                String.valueOf(inventory.getId()),
                String.valueOf(inventory.getProductId()),
                String.valueOf(inventory.getBranchId()),
                inventory.getQuantityOnHand(),
                inventory.getAverageUnitCost(),
                inventory.getMinimumStock(),
                inventory.getUpdatedAt());
    }
}
