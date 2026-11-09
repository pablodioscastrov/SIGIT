package com.inventario.multisucursal.purchases;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * {@code quantityReceived} sin {@code @DecimalMin} por el mismo motivo que
 * {@code CreatePurchaseOrderItemRequest.quantityOrdered} (BR-012, 422
 * {@code CANTIDAD_INVALIDA} en {@code PurchaseReceiptService}).
 * {@code unitPrice} aquí es el costo efectivamente recibido en esta
 * recepción — puede diferir del {@code unitPrice} pactado en la orden; se
 * usa exclusivamente para recalcular {@code Inventory.average_unit_cost}
 * (BR-004), nunca sobrescribe el de la línea original (docs/openapi.yaml,
 * PurchaseReceiptRequest).
 */
public record ReceiptItemRequest(
        @NotNull Long purchaseOrderItemId,
        @NotNull BigDecimal quantityReceived,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal unitPrice) {
}
