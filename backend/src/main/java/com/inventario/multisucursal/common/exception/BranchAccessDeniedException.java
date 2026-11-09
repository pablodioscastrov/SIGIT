package com.inventario.multisucursal.common.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * Subtipo específico de {@link AccessDeniedException} para distinguir "el rol
 * no permite esta acción" (ROL_NO_AUTORIZADO) de "el rol la permite, pero no
 * sobre esta sucursal" (SUCURSAL_NO_AUTORIZADA) — docs/BUSINESS_RULES.md,
 * BR-018. Vive en {@code common} (no en {@code auth}) porque cualquier módulo
 * de negocio futuro con recursos con dueño de sucursal la lanzará, no solo la
 * autenticación.
 */
public class BranchAccessDeniedException extends AccessDeniedException {

    public BranchAccessDeniedException(String message) {
        super(message);
    }
}
