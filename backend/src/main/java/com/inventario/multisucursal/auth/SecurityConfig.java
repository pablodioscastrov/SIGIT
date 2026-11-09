package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.common.web.JsonAccessDeniedHandler;
import com.inventario.multisucursal.common.web.JsonAuthenticationEntryPoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * TD-007/TD-008 (docs/DECISIONS.md), ADR-005 (docs/adr/ADR-005-jwt-rbac.md):
 * JWT propio y stateless, RBAC declarativo. No hay servidor OAuth2 externo,
 * no hay sesión de servidor (sin cookies, por eso CSRF se deshabilita — es
 * una protección relevante solo cuando la autenticación viaja en una cookie
 * que el navegador reenvía automáticamente).
 *
 * <p><b>CORS:</b> el frontend se sirve desde un origen distinto al de la API
 * (nginx en un puerto, Spring Boot en otro), así que el navegador exige una
 * política explícita — sin ella bloquea toda petición, incluido el login.
 * Los orígenes permitidos llegan por configuración (`app.cors.allowed-origins`),
 * nunca hardcodeados, para que cada entorno declare el suyo.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt: adaptativo (factor de costo configurable), con sal por
        // registro incluida en el propio hash - el estándar de la industria
        // para contraseñas, evita guardarlas en texto plano (ADR-005).
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(AppUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Orígenes autorizados a llamar a la API desde un navegador. Se listan de
     * forma explícita (nunca `*`): un comodín permitiría que cualquier sitio
     * web hiciera peticiones con el token de la persona usuaria si lograra
     * leerlo.
     */
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        // Idempotency-Key es obligatorio en las operaciones de creación repetible
        // (docs/API_DESIGN.md, sección 2); sin declararlo, el preflight lo rechazaría.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id", "Accept"));
        // Se expone el identificador de correlación para que el cliente pueda
        // mostrarlo y cruzarlo con los logs del backend. Content-Disposition se
        // expone para que el cliente lea el nombre de archivo de un reporte
        // exportado (BR-056) — sin esto, `fetch` recibe el blob pero no puede
        // leer el encabezado con el nombre sugerido por el servidor.
        configuration.setExposedHeaders(List.of("X-Request-Id", "Content-Disposition"));
        // Sin credenciales: la autenticación viaja en el encabezado Authorization,
        // no en cookies, así que el navegador no necesita enviarlas.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
