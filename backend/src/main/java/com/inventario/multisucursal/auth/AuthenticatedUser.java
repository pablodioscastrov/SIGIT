package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.users.RoleCode;

/**
 * Identidad reconstruida directamente desde los claims del JWT en cada
 * request (ver {@link JwtAuthenticationFilter}) — deliberadamente no se
 * vuelve a consultar la base de datos en cada petición autenticada (diseño
 * stateless, docs/adr/ADR-005-jwt-rbac.md). Es el tipo de {@code principal}
 * que usan las reglas de autorización por sucursal (BR-018).
 */
public record AuthenticatedUser(Long userId, String name, String email, RoleCode role, Long branchId) {
}
