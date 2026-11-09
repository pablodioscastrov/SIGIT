package com.inventario.multisucursal.users;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-14: desactivar exige explicar el motivo, que queda visible mientras el usuario siga desactivado. */
public record DeactivateUserRequest(@NotBlank @Size(max = 500) String reason) {
}
