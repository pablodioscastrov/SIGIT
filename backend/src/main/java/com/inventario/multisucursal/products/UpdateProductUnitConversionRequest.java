package com.inventario.multisucursal.products;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateProductUnitConversionRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 13, fraction = 6) BigDecimal conversionFactorToBase) {
}
