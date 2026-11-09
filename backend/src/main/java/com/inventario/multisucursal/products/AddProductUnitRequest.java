package com.inventario.multisucursal.products;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * {@code conversionFactorToBase} debe ser estrictamente positivo (BR-011:
 * "conversiones deben ser deterministas y validadas") — se valida aquí, en
 * la forma del payload (400 si falla), no como regla de negocio (422),
 * porque es una propiedad estructural del número en sí, no del estado del
 * sistema.
 */
public record AddProductUnitRequest(
        @NotNull Long unitOfMeasureId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 13, fraction = 6) BigDecimal conversionFactorToBase) {
}
