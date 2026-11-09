package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Login rechazado porque la cuenta existe pero está desactivada — por
 * instrucción explícita, distinto de {@link InvalidCredentialsException}
 * (email inexistente o contraseña incorrecta). Nota de seguridad: a
 * diferencia del resto de fallos de login (deliberadamente genéricos para no
 * revelar si un correo está registrado), esto sí confirma que la cuenta
 * existe — riesgo de enumeración aceptado explícitamente a cambio de que la
 * persona sepa que debe contactar a un administrador o gerente, en vez de
 * reintentar una contraseña que nunca funcionará.
 */
public class DisabledAccountException extends ApiException {

    public DisabledAccountException() {
        super(HttpStatus.UNAUTHORIZED, "CUENTA_DESACTIVADA", "Tu cuenta está desactivada. Contacta a un administrador o gerente.");
    }
}
