package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.users.User;

/**
 * Forma pública del usuario autenticado (docs/API_DESIGN.md: {@code UserSummary}),
 * usada tanto en la respuesta de login como en {@code GET /auth/me}. IDs como
 * string, por la convención general de la API (docs/API_DESIGN.md, sección 1).
 */
public record UserSummaryResponse(String id, String name, String email, String role, String branchId) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(
                String.valueOf(user.getId()),
                user.getName(),
                user.getEmail(),
                user.getRoleCode().name(),
                user.getBranchId() != null ? String.valueOf(user.getBranchId()) : null);
    }

    public static UserSummaryResponse from(AuthenticatedUser user) {
        return new UserSummaryResponse(
                String.valueOf(user.userId()),
                user.name(),
                user.email(),
                user.role().name(),
                user.branchId() != null ? String.valueOf(user.branchId()) : null);
    }
}
