package com.inventario.multisucursal.common.web;

/**
 * Un campo específico afectado por un error de validación (docs/API_DESIGN.md,
 * sección 3). Solo se usa cuando un error involucra más de un campo.
 */
public record ErrorDetail(String field, String issue) {
}
