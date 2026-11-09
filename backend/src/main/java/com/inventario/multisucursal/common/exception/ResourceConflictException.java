package com.inventario.multisucursal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * El estado actual del recurso impide la operación: transición inválida, ya
 * aplicada (idempotencia de categoría 1), conflicto de versión por
 * concurrencia (docs/BUSINESS_RULES.md, convención de errores: 409).
 *
 * <p>El {@code code} siempre lo decide quien lanza la excepción (p. ej.
 * {@code TRANSICION_INVALIDA}, {@code ORDEN_YA_RECIBIDA},
 * {@code CONFLICTO_CONCURRENCIA}) — esta clase no asume ningún caso concreto.
 */
public class ResourceConflictException extends ApiException {

    public ResourceConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
