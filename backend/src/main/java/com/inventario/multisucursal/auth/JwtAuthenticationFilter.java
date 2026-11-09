package com.inventario.multisucursal.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Autenticación stateless por JWT (docs/adr/ADR-005-jwt-rbac.md): si el
 * header {@code Authorization: Bearer <token>} contiene un JWT válido, se
 * reconstruye la identidad directamente desde sus claims (sin consultar la
 * base de datos) y se registra en el {@link SecurityContextHolder}.
 *
 * <p>Un token ausente, expirado o manipulado simplemente deja el contexto de
 * seguridad vacío — no se lanza ningún error aquí. La regla
 * {@code anyRequest().authenticated()} de {@link SecurityConfig} es la que,
 * más adelante en la cadena, rechaza la petición con 401 a través de
 * {@link JsonAuthenticationEntryPoint} si la ruta lo exige.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String EVENT_STREAM_PATH = "/api/v1/events";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null) {
            try {
                AuthenticatedUser user = jwtService.parseToken(token);
                List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
                var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ex) {
                log.debug("Token JWT inválido o expirado: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * El token viaja en {@code Authorization: Bearer ...} en toda la API,
     * salvo en el canal SSE: {@code EventSource} (estándar del navegador) no
     * permite fijar encabezados, así que ahí se acepta también
     * {@code ?access_token=...} — excepción ya documentada y aceptada en
     * docs/API_DESIGN.md, sección 2.
     *
     * <p>La excepción se limita a esa única ruta a propósito: un token en la
     * query string puede quedar registrado en logs de acceso de proxies
     * intermedios, así que no debe habilitarse donde no hace falta. La mejora
     * futura que ya señala ese documento —un token de vida muy corta
     * exclusivo para esta conexión— sigue pendiente y no se resuelve aquí.
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        if (EVENT_STREAM_PATH.equals(request.getRequestURI())) {
            String queryToken = request.getParameter("access_token");
            if (queryToken != null && !queryToken.isBlank()) {
                return queryToken;
            }
        }
        return null;
    }
}
