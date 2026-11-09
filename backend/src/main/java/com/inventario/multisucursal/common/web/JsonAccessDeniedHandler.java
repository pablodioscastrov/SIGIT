package com.inventario.multisucursal.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventario.multisucursal.common.exception.BranchAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Cubre el caso (poco común) en que una denegación de acceso ocurre fuera de
 * la invocación de un controlador — el caso normal, denegación por
 * {@code @PreAuthorize} dentro de un método de controlador, lo cubre
 * {@link GlobalExceptionHandler#handleAccessDenied}, con el mismo formato y
 * la misma distinción {@code ROL_NO_AUTORIZADO} / {@code SUCURSAL_NO_AUTORIZADA}
 * (BR-018).
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        if (accessDeniedException instanceof BranchAccessDeniedException) {
            ApiErrorSupport.write(objectMapper, response, 403, "SUCURSAL_NO_AUTORIZADA", accessDeniedException.getMessage());
        } else {
            ApiErrorSupport.write(objectMapper, response, 403, "ROL_NO_AUTORIZADO", "No tiene permisos para realizar esta acción.");
        }
    }
}
