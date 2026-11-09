package com.inventario.multisucursal.inventory;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * docs/API_DESIGN.md, sección 8: InventoryMovementDTO. {@code source} es
 * {@code null} para un ajuste manual (BR-023, sin documento comercial);
 * para una recepción de compra queda poblado con {@code type=PURCHASE_ORDER}
 * y el id de la {@code PurchaseOrderItem} de origen; para una venta,
 * {@code type=SALE} y el id de la {@code SaleItem}; para una transferencia,
 * {@code type=TRANSFER} y el id de la {@code TransferItem} (la misma línea
 * aparece en dos movimientos: la salida del origen y la entrada al destino).
 */
public record InventoryMovementResponse(
        String id,
        String productId,
        String branchId,
        MovementDirection direction,
        MovementReason reason,
        BigDecimal quantity,
        String unitOfMeasureId,
        String responsibleUserId,
        Instant occurredAt,
        String notes,
        MovementSource source) {

    public static InventoryMovementResponse from(InventoryMovement movement) {
        MovementSource source;
        if (movement.getPurchaseOrderItemId() != null) {
            source = new MovementSource("PURCHASE_ORDER", String.valueOf(movement.getPurchaseOrderItemId()));
        } else if (movement.getSaleItemId() != null) {
            source = new MovementSource("SALE", String.valueOf(movement.getSaleItemId()));
        } else if (movement.getTransferItemId() != null) {
            source = new MovementSource("TRANSFER", String.valueOf(movement.getTransferItemId()));
        } else {
            source = null;
        }
        return new InventoryMovementResponse(
                String.valueOf(movement.getId()),
                String.valueOf(movement.getProductId()),
                String.valueOf(movement.getBranchId()),
                movement.getDirection(),
                movement.getReason(),
                movement.getQuantity(),
                String.valueOf(movement.getUnitOfMeasureId()),
                String.valueOf(movement.getResponsibleUserId()),
                movement.getOccurredAt(),
                movement.getNotes(),
                source);
    }

    public record MovementSource(String type, String id) {
    }
}
