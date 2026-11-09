package com.inventario.multisucursal.branches;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code code} no es editable (es la clave de negocio inmutable de la
 * sucursal, docs/API_DESIGN.md sección 7.3) — este PATCH reemplaza nombre y
 * ubicación en conjunto (no es un merge-patch parcial campo por campo).
 */
public record UpdateBranchRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 255) String location) {
}
