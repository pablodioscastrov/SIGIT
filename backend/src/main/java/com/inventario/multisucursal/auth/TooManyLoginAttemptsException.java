package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Auditoría de seguridad — {@code /auth/login} no tenía ningún límite de
 * intentos, permitiendo fuerza bruta/credential-stuffing ilimitado contra
 * una cuenta. Mismo mensaje genérico independientemente de si el correo
 * existe, igual criterio que {@link InvalidCredentialsException}.
 */
public class TooManyLoginAttemptsException extends ApiException {

    public TooManyLoginAttemptsException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "DEMASIADOS_INTENTOS",
                "Demasiados intentos fallidos. Espera unos minutos antes de volver a intentarlo.");
    }
}
