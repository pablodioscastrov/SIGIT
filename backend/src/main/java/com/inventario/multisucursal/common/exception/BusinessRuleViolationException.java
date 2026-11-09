package com.inventario.multisucursal.common.exception;

import com.inventario.multisucursal.common.web.ErrorDetail;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * El payload es válido en su forma, pero viola una regla de negocio semántica
 * (docs/BUSINESS_RULES.md, convención de errores: 422) — p. ej.
 * {@code STOCK_INSUFICIENTE}, {@code CANTIDAD_INVALIDA},
 * {@code DESCUENTO_FUERA_DE_RANGO}. El {@code code} y el mensaje siempre los
 * decide el servicio del módulo que detecta la violación.
 */
public class BusinessRuleViolationException extends ApiException {

    public BusinessRuleViolationException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public BusinessRuleViolationException(String code, String message, List<ErrorDetail> details) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }
}
