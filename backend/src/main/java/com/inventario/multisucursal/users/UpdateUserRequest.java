package com.inventario.multisucursal.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * docs/API_DESIGN.md, sección 7.2: el PATCH actualiza "nombre/correo/rol/sucursal"
 * (BR-058, por instrucción explícita — el correo se sumó a este alcance; la
 * contraseña sigue siendo un flujo aparte, fuera de esto).
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull RoleCode role,
        Long branchId) {
}
