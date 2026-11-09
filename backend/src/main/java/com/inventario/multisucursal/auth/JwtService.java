package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.users.RoleCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Emisión y validación de JWT propios, firmados con HS256 (clave simétrica
 * compartida) — no hay servidor OAuth2 externo ni issuer/JWK que gestionar
 * (ADR-005). Claims mínimos, sin datos sensibles: {@code sub} (id de
 * usuario), {@code name}, {@code email}, {@code role}, {@code branchId}
 * (ausente para ADMIN). Nunca se incluye el hash de contraseña ni ningún otro
 * dato sensible.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /**
     * Mismo valor por defecto que {@code application.yml}/{@code docker-compose.yml}/
     * {@code .env.example} — auditoría de seguridad: no se rechaza el arranque con
     * este valor (RT-003 exige que `docker compose up` funcione sin configuración
     * manual adicional, y hoy es exactamente ese el flujo que lo usa), pero un
     * despliegue real que lo conserve queda con las claves de firma de cualquier
     * JWT publicadas en el propio repositorio — se advierte en el arranque para
     * que no pase desapercibido.
     */
    private static final String INSECURE_DEFAULT_SECRET = "dev-only-insecure-secret-please-override-with-JWT_SECRET-env-var-2026";

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(JwtProperties properties) {
        if (INSECURE_DEFAULT_SECRET.equals(properties.secret())) {
            log.warn("*** JWT_SECRET no está configurado: se está usando el valor por defecto de desarrollo, "
                    + "publicado en el repositorio. Cualquiera puede forjar tokens válidos, incluido role=ADMIN. "
                    + "Defina JWT_SECRET (openssl rand -base64 48) antes de exponer este backend fuera de un entorno local. ***");
        }
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMs = properties.expirationMs();
    }

    public String generateToken(AuthenticatedUser user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .claim("name", user.name())
                .claim("email", user.email())
                .claim("role", user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key);

        if (user.branchId() != null) {
            builder.claim("branchId", user.branchId());
        }

        return builder.compact();
    }

    /**
     * @throws JwtException si el token está expirado, mal formado, o su firma
     *                       no corresponde a esta clave (manipulado).
     */
    public AuthenticatedUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = Long.valueOf(claims.getSubject());
        String name = claims.get("name", String.class);
        String email = claims.get("email", String.class);
        RoleCode role = RoleCode.valueOf(claims.get("role", String.class));
        Number branchIdClaim = claims.get("branchId", Number.class);
        Long branchId = branchIdClaim != null ? branchIdClaim.longValue() : null;

        return new AuthenticatedUser(userId, name, email, role, branchId);
    }

    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }
}
