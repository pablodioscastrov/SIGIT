package com.inventario.multisucursal.products;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code code} es la clave de negocio y no se edita por esta vía — mismo criterio que {@code Product.sku}. */
public record UpdateUnitOfMeasureRequest(@NotBlank @Size(max = 100) String name) {
}
