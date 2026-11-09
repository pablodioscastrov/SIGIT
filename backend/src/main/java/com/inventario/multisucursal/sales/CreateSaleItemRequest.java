package com.inventario.multisucursal.sales;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * {@code quantity} sin {@code @DecimalMin} — BR-012 exige que "cantidad ≤ 0"
 * en {@code SaleItem.quantity} sea un rechazo de negocio con código propio
 * (422 {@code CANTIDAD_INVALIDA}), no el genérico {@code VALIDATION_ERROR}
 * de Bean Validation; {@link SaleService} lo valida explícitamente (mismo
 * tratamiento que {@code PurchaseOrderItem.quantityOrdered}).
 * {@code discountPercentage} sí es estructural (BR-019, docs/openapi.yaml).
 */
public record CreateSaleItemRequest(
        @NotNull Long productId,
        Long unitOfMeasureId,
        @NotNull BigDecimal quantity,
        @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal discountPercentage) {
}
