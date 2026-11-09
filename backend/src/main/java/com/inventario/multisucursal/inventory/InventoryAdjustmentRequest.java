package com.inventario.multisucursal.inventory;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * docs/openapi.yaml, InventoryAdjustmentRequest. {@code quantity} y
 * {@code notes} deliberadamente no llevan {@code @DecimalMin}/{@code @NotBlank}
 * aquí: BR-012 y BR-023 exigen que "cantidad ≤ 0" y "motivo en blanco" sean
 * rechazos de negocio con código propio (422 {@code CANTIDAD_INVALIDA}, 400
 * {@code NOTES_REQUERIDO}), no el genérico {@code VALIDATION_ERROR} de Bean
 * Validation — {@link InventoryMovementService} los valida explícitamente.
 * {@code unitOfMeasureId} y {@code reason} son opcionales (ver servicio para
 * los valores por defecto).
 */
public record InventoryAdjustmentRequest(
        @NotNull Long branchId,
        @NotNull Long productId,
        Long unitOfMeasureId,
        @NotNull MovementDirection direction,
        MovementReason reason,
        @NotNull BigDecimal quantity,
        String notes) {
}
