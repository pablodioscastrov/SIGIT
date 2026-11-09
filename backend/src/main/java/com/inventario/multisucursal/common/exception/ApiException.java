package com.inventario.multisucursal.common.exception;

import com.inventario.multisucursal.common.web.ErrorDetail;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Base de las excepciones que {@link com.inventario.multisucursal.common.web.GlobalExceptionHandler}
 * traduce a la respuesta de error uniforme (docs/API_DESIGN.md, sección 3).
 *
 * <p>Esta clase decide únicamente <b>cómo</b> se ve un error (status, code,
 * details) — nunca <b>cuándo</b> se lanza cada una. Esa decisión de negocio
 * (qué código exacto, con qué mensaje, en qué caso) le corresponde siempre al
 * servicio de cada módulo, no a esta jerarquía.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ErrorDetail> details;

    protected ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    protected ApiException(HttpStatus status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ErrorDetail> getDetails() {
        return details;
    }
}
