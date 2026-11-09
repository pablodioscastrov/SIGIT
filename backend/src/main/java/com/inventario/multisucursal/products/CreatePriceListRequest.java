package com.inventario.multisucursal.products;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code branchId} nulo crea una lista global (docs/DOMAIN_MODEL.md, sección 2.13). */
public record CreatePriceListRequest(@NotBlank @Size(max = 150) String name, Long branchId) {
}
