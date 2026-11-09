package com.inventario.multisucursal.suppliers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code taxId} es inmutable vía este endpoint — misma convención que {@code Product.sku}. */
public record UpdateSupplierRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 150) String contactName,
        @Size(max = 30) String phone,
        @Email @Size(max = 255) String email) {
}
