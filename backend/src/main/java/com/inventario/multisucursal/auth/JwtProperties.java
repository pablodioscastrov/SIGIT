package com.inventario.multisucursal.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code secret} nunca debe tener el valor por defecto de {@code application.yml}
 * fuera de desarrollo local — se sobrescribe con la variable de entorno
 * {@code JWT_SECRET} (ver .env.example). Debe tener al menos 256 bits (32
 * caracteres) para HS256; una clave más corta hace fallar el arranque
 * (ver {@link JwtService}).
 *
 * <p>{@code expirationMs} resuelve la decisión, antes pospuesta, de política
 * de expiración (docs/ARCHITECTURE.md, "Decisiones pospuestas"): tokens de
 * vida corta porque no existe revocación (ADR-005) ni refresh token en este
 * alcance (docs/API_DESIGN.md solo define login + me).
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long expirationMs) {
}
