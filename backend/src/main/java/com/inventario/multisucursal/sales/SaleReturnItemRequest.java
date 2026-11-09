package com.inventario.multisucursal.sales;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** {@code quantity} sin {@code @DecimalMin}: BR-052 exige el mismo rechazo de negocio con código propio (422 {@code CANTIDAD_INVALIDA}) que el resto de cantidades del módulo. */
public record SaleReturnItemRequest(@NotNull Long saleItemId, @NotNull BigDecimal quantity) {
}
