package com.inventario.multisucursal.users;

/**
 * Forma pública para gestión administrativa de usuarios (distinta de
 * {@code auth.UserSummaryResponse}, que es el perfil de la sesión actual y no
 * expone {@code active} — aquí sí importa, UC-14 necesita listar quién está
 * activo/inactivo).
 */
public record UserResponse(
        String id, String name, String email, String role, String branchId, boolean active, String deactivationReason) {

    public static UserResponse from(User user) {
        return new UserResponse(
                String.valueOf(user.getId()),
                user.getName(),
                user.getEmail(),
                user.getRoleCode().name(),
                user.getBranchId() != null ? String.valueOf(user.getBranchId()) : null,
                user.isActive(),
                user.getDeactivationReason());
    }
}
