package com.inventario.multisucursal.users;

/**
 * Los tres roles RBAC ya aprobados (TD-008, docs/DECISIONS.md). El catálogo
 * de referencia en base de datos ({@code role}, sembrado por Flyway) existe
 * únicamente para integridad referencial (FK desde {@code users.role_code});
 * la aplicación no necesita cargar/manipular esas filas en tiempo de
 * ejecución, por eso se modela como un enum aquí en vez de una entidad JPA
 * adicional sin uso real. {@code displayName} solo respalda
 * {@code GET /api/v1/roles} (docs/API_DESIGN.md, sección 7.2) y refleja los
 * mismos nombres sembrados en V1__create_role_table.sql.
 */
public enum RoleCode {
    ADMIN("Administrador general"),
    MANAGER("Gerente de sucursal"),
    OPERATOR("Operador de inventario");

    private final String displayName;

    RoleCode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
