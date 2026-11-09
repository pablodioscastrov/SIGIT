package com.inventario.multisucursal.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Resuelve el identificador de correlación de cada request (docs/API_DESIGN.md,
 * sección 2): si el cliente envía {@code X-Request-Id} se reutiliza, si no se
 * genera uno. Se expone en la respuesta y se deja disponible en el MDC para
 * que aparezca en cada línea de log de la solicitud (ver logging.pattern en
 * application.yml) sin necesitar un stack de logging estructurado (JSON).
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)}: debe ejecutarse antes que la cadena
 * de filtros de Spring Security. Sin esto, un request rechazado dentro de esa
 * cadena (p. ej. sin token, 401) nunca llegaría a este filtro y su respuesta
 * de error quedaría sin {@code requestId} — el filtro de seguridad corta la
 * cadena antes de que un filtro registrado con orden por defecto (más tardío)
 * alcance a ejecutarse.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER_NAME);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
