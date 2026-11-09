package com.inventario.multisucursal.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Se activa cuando una ruta protegida se solicita sin autenticación válida
 * (token ausente, expirado o manipulado) — docs/API_DESIGN.md, sección 4:
 * 401 {@code NO_AUTENTICADO}. Este componente cubre el caso en que ni
 * siquiera se llega a invocar un controlador (la cadena de filtros de
 * seguridad corta la petición antes); el caso en que la excepción ocurre
 * dentro de la invocación de un controlador (p. ej. una anotación de método)
 * lo cubre {@link GlobalExceptionHandler#handleAuthentication}, con el mismo
 * formato de salida.
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ApiErrorSupport.write(objectMapper, response, 401, "NO_AUTENTICADO", "Token ausente, inválido o expirado.");
    }
}
