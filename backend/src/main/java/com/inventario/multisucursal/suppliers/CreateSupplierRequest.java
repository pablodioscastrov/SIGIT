package com.inventario.multisucursal.suppliers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSupplierRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 50) String taxId,
        @Size(max = 150) String contactName,
        @Size(max = 30) String phone,
        @Email @Size(max = 255) String email) {
}
