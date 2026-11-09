package com.inventario.multisucursal.events;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.users.RoleCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registro en memoria de los clientes conectados por SSE y reparto de señales
 * hacia ellos (ADR-007).
 *
 * <p><b>Sin infraestructura nueva</b>: el registro vive en el proceso, sin
 * Kafka/Redis/RabbitMQ, tal como exige ADR-007 para este volumen. La
 * contrapartida honesta es que el reparto solo alcanza a los clientes
 * conectados a <i>esta</i> instancia; hoy Docker Compose levanta una sola, y
 * el día que se escale horizontalmente habrá que introducir un bus — momento
 * en el que ADR-007 debe reconsiderarse, no antes.
 *
 * <p><b>El canal nunca puede romper una operación de negocio.</b> Dos
 * defensas: se ejecuta después del commit (la transacción ya terminó bien) y
 * toda excepción de envío se captura aquí, jamás se propaga al hilo que
 * ejecutó la venta o la transferencia. Un cliente cuya conexión falló se
 * descarta del registro y volverá por su cuenta — {@code EventSource}
 * reconecta solo.
 */
@Component
public class EventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);

    private final ConcurrentHashMap<Long, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong subscriptionIds = new AtomicLong();

    /**
     * @param branchFilter sucursales que el cliente pidió seguir; vacío = todas
     *        aquellas a las que tenga derecho
     */
    public long register(AuthenticatedUser user, Set<Long> branchFilter, SseEmitter emitter) {
        long id = subscriptionIds.incrementAndGet();
        subscriptions.put(id, new Subscription(user, branchFilter, emitter));
        emitter.onCompletion(() -> unregister(id));
        emitter.onTimeout(() -> unregister(id));
        emitter.onError(throwable -> unregister(id));
        return id;
    }

    public void unregister(long subscriptionId) {
        subscriptions.remove(subscriptionId);
    }

    public int activeSubscriptions() {
        return subscriptions.size();
    }

    /**
     * Se dispara únicamente tras un commit exitoso. Si la transacción hace
     * rollback, este método no llega a ejecutarse y la señal no se emite.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEvent event) {
        broadcast(event);
    }

    /**
     * Latido periódico hacia todos los clientes conectados.
     *
     * <p>Cumple dos funciones, ambas necesarias: (1) el servidor solo descubre
     * que un cliente se fue cuando intenta escribirle — sin latido, una
     * conexión muerta quedaría ocupando memoria hasta agotar el timeout de
     * 30 minutos; (2) muchos proxies cierran una conexión HTTP inactiva a los
     * pocos minutos, y un canal SSE puede estar legítimamente en silencio
     * mucho más que eso.
     *
     * <p>Se envía como <b>comentario</b> SSE, que el estándar
     * {@code EventSource} descarta: mantiene viva la conexión sin que el
     * cliente tenga que conocer ni filtrar un tipo de evento artificial.
     */
    @Scheduled(fixedDelayString = "${app.events.heartbeat-interval-ms:30000}")
    public void heartbeat() {
        subscriptions.forEach((id, subscription) -> {
            try {
                subscription.emitter().send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception disconnected) {
                log.debug("Suscripción SSE {} descartada en el latido: {}", id, disconnected.toString());
                unregister(id);
            }
        });
    }

    /** Visible para pruebas: permite ejercer el reparto sin montar una transacción. */
    void broadcast(DomainEvent event) {
        EventPayload payload = EventPayload.from(event);
        subscriptions.forEach((id, subscription) -> {
            if (!subscription.shouldReceive(event)) {
                return;
            }
            try {
                subscription.emitter().send(SseEmitter.event().name(event.type()).data(payload));
            } catch (Exception sendFailure) {
                // La conexión se cayó o el cliente se fue. No es un error de negocio:
                // se descarta la suscripción y el cliente reconciliará por REST al reconectar.
                log.debug("Suscripción SSE {} descartada al enviar {}: {}", id, event.type(), sendFailure.toString());
                unregister(id);
            }
        });
    }

    private record Subscription(AuthenticatedUser user, Set<Long> branchFilter, SseEmitter emitter) {

        /**
         * Autorización del canal, espejo exacto de la de la API REST
         * (docs/API_DESIGN.md, sección 6): SSE no concede visibilidad que el
         * usuario no tuviera ya consultando.
         */
        boolean shouldReceive(DomainEvent event) {
            if (!branchFilter.isEmpty() && event.branchIds().stream().noneMatch(branchFilter::contains)) {
                return false;
            }
            if (!event.branchRestricted() || user.role() == RoleCode.ADMIN) {
                return true;
            }
            return user.branchId() != null && event.branchIds().contains(user.branchId());
        }
    }
}
