package com.inventario.multisucursal.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auditoría de seguridad: {@code POST /auth/login} no tenía ningún límite de
 * intentos — permitía fuerza bruta/credential-stuffing ilimitado contra una
 * cuenta. Bloqueo simple en memoria por correo (normalizado a minúsculas),
 * suficiente para el despliegue actual (un solo proceso, Docker Compose
 * local, sin balanceo entre instancias — mismo criterio ya aceptado para el
 * canal SSE en {@code EventBroadcaster}); si el proyecto llegara a correr
 * varias instancias del backend, esto tendría que moverse a un almacén
 * compartido.
 *
 * <p>Se cuenta cualquier fallo de autenticación (contraseña incorrecta,
 * correo inexistente o cuenta desactivada) contra el mismo correo — no
 * distingue el motivo, igual criterio de no revelar información que ya usa
 * {@link InvalidCredentialsException}.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final Map<String, Attempts> attemptsByEmail = new ConcurrentHashMap<>();

    public void checkNotBlocked(String email) {
        Attempts attempts = attemptsByEmail.get(normalize(email));
        if (attempts != null && attempts.isBlocked()) {
            throw new TooManyLoginAttemptsException();
        }
    }

    public void recordFailure(String email) {
        attemptsByEmail.compute(normalize(email), (key, existing) -> {
            Attempts attempts = existing != null ? existing : new Attempts();
            attempts.registerFailure();
            return attempts;
        });
    }

    public void recordSuccess(String email) {
        attemptsByEmail.remove(normalize(email));
    }

    /** Evita que intentos contra correos inexistentes hagan crecer el mapa sin límite. */
    @Scheduled(fixedDelayString = "${app.auth.login-rate-limit-cleanup-ms:600000}")
    void evictExpiredEntries() {
        attemptsByEmail.values().removeIf(Attempts::isExpired);
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Attempts {
        private int count;
        private Instant windowStart = Instant.now();
        private Instant blockedUntil;

        synchronized void registerFailure() {
            Instant now = Instant.now();
            if (Duration.between(windowStart, now).compareTo(ATTEMPT_WINDOW) > 0) {
                count = 0;
                windowStart = now;
                blockedUntil = null;
            }
            count++;
            if (count >= MAX_FAILED_ATTEMPTS) {
                blockedUntil = now.plus(LOCKOUT_DURATION);
            }
        }

        synchronized boolean isBlocked() {
            return blockedUntil != null && Instant.now().isBefore(blockedUntil);
        }

        synchronized boolean isExpired() {
            return !isBlocked() && Duration.between(windowStart, Instant.now()).compareTo(ATTEMPT_WINDOW) > 0;
        }
    }
}
