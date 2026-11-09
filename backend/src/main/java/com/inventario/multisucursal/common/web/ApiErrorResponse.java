package com.inventario.multisucursal.common.web;

/**
 * Sobre uniforme de error de toda la API: {@code {"error": {...}}}
 * (docs/API_DESIGN.md, sección 3). Ningún endpoint debe devolver un error con
 * una forma distinta a esta.
 */
public record ApiErrorResponse(ApiErrorBody error) {
}
