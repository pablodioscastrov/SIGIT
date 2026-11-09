package com.inventario.multisucursal.transfers;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * docs/openapi.yaml, {@code TransferCreateRequest.items[]}. No lleva
 * {@code unitOfMeasureId}: el contrato aprobado no lo define para
 * transferencias (a diferencia de compras/ventas), así que la línea siempre
 * se registra en la unidad base del producto — sin conversión que aplicar.
 *
 * <p>{@code quantityRequested} sin {@code @DecimalMin}: BR-012 exige que
 * "cantidad ≤ 0" sea 422 {@code CANTIDAD_INVALIDA}, no el genérico
 * {@code VALIDATION_ERROR} (mismo criterio que en compras y ventas).
 */
public record CreateTransferItemRequest(@NotNull Long productId, @NotNull BigDecimal quantityRequested) {
}
