package com.inventario.multisucursal.purchases;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * {@code quantityOrdered} deliberadamente no lleva {@code @DecimalMin} —
 * BR-012 exige que "cantidad ≤ 0" sea un rechazo de negocio con código
 * propio (422 {@code CANTIDAD_INVALIDA}), no el genérico
 * {@code VALIDATION_ERROR} de Bean Validation; {@link PurchaseOrderService}
 * lo valida explícitamente. {@code unitPrice}/{@code discountPercentage} sí
 * son estructurales (BR-019, docs/openapi.yaml): un precio no positivo o un
 * descuento fuera de [0,100] es una propiedad de forma del número, no del
 * estado del sistema.
 */
public record CreatePurchaseOrderItemRequest(
        @NotNull Long productId,
        Long unitOfMeasureId,
        @NotNull BigDecimal quantityOrdered,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal unitPrice,
        @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal discountPercentage) {
}
