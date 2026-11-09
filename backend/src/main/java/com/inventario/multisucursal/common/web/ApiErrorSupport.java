package com.inventario.multisucursal.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Escribe el sobre uniforme de error directamente sobre
 * {@link HttpServletResponse}, para los dos puntos de la cadena de Spring
 * Security ({@link JsonAuthenticationEntryPoint}, {@link JsonAccessDeniedHandler})
 * que se ejecutan fuera del pipeline normal de Spring MVC y por lo tanto no
 * pueden simplemente devolver un {@code ResponseEntity} como
 * {@link GlobalExceptionHandler}. Es la única razón de ser de esta clase — no
 * decide ningún código ni mensaje, solo el mecanismo de escritura.
 */
final class ApiErrorSupport {

    private ApiErrorSupport() {
    }

    static void write(ObjectMapper objectMapper, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
        ApiErrorBody body = new ApiErrorBody(code, message, status, requestId, List.of());

        response.setStatus(status);
        // charset explícito: response.getWriter() usa ISO-8859-1 por defecto
        // (default del Servlet spec) si no se fija antes de pedirlo, lo que
        // corrompía cualquier tilde/ñ en el mensaje (a diferencia de las
        // respuestas que sí pasan por el HttpMessageConverter de Spring MVC
        // en GlobalExceptionHandler, que ya usa UTF-8 por defecto).
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(body));
    }
}
