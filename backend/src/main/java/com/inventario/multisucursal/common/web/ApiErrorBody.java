package com.inventario.multisucursal.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Cuerpo del error, tal como lo define docs/API_DESIGN.md, sección 3.
 * {@code details} se omite del JSON cuando está vacío en vez de serializarse
 * como lista vacía — mantiene la respuesta limpia para el caso común de un
 * único error sin desglose por campo.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorBody(
        String code,
        String message,
        int status,
        String requestId,
        List<ErrorDetail> details) {
}
