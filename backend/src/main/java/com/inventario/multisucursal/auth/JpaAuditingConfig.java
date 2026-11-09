package com.inventario.multisucursal.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Habilita el llenado automático de {@code createdBy}/{@code updatedBy} en
 * las entidades que extienden {@link com.inventario.multisucursal.common.audit.Auditable}.
 * Vive en {@code auth} (no en {@code common.audit}, donde vive la clase base
 * {@code Auditable} en sí) porque resolver "quién es el usuario actual" es
 * un conocimiento de autenticación, no de la infraestructura de auditoría
 * genérica — {@code common} no debe depender de {@code auth}.
 *
 * <p>Ahora que el módulo {@code auth} existe, el auditor es el email del
 * usuario autenticado ({@link AuthenticatedUser}, resuelto por
 * {@link JwtAuthenticationFilter} desde el JWT). El placeholder
 * {@code "system"} solo se usa como resguardo para escrituras que ocurran
 * sin una autenticación en el contexto (p. ej. pruebas de persistencia
 * directas) — no debería aparecer en una petición HTTP real, dado que
 * {@code SecurityConfig} exige autenticación para toda ruta salvo el login.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
                return Optional.of(user.email());
            }
            return Optional.of("system");
        };
    }
}
