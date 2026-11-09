package com.inventario.multisucursal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * El recurso referenciado por identificador no existe (docs/BUSINESS_RULES.md,
 * convención de errores: 404). Código por defecto RECURSO_NO_ENCONTRADO; un
 * módulo puede pasar un código más específico si lo necesita.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO", message);
    }

    public ResourceNotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
