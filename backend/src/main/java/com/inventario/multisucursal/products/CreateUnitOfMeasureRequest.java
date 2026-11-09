package com.inventario.multisucursal.products;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUnitOfMeasureRequest(
        @NotBlank @Size(max = 10) String code,
        @NotBlank @Size(max = 100) String name) {
}
