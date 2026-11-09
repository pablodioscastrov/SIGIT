package com.inventario.multisucursal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * El payload tiene forma válida para Bean Validation, pero una regla de
 * negocio exige un código específico distinto del genérico
 * {@code VALIDATION_ERROR} para el mismo status 400 (p. ej. BR-023,
 * {@code NOTES_REQUERIDO}: un motivo en blanco no es un error de tipo/formato,
 * pero tampoco es una violación semántica sobre datos existentes — sigue
 * siendo un defecto estructural del payload, no un 422).
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
