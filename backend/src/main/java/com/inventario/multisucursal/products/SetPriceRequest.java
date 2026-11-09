package com.inventario.multisucursal.products;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SetPriceRequest(@NotNull Long productId, @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal unitPrice) {
}
