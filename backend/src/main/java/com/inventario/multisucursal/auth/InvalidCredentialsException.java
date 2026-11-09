package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Login fallido: email inexistente o contraseña incorrecta. Deliberadamente
 * el mismo código y mensaje genérico para ambos casos — distinguirlos
 * revelaría a un atacante si un correo está registrado (enumeración de
 * usuarios), un riesgo de seguridad innecesario. Una cuenta desactivada usa
 * {@link DisabledAccountException} en su lugar (BR-055, distinta por
 * instrucción explícita).
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS", "Credenciales inválidas.");
    }
}
