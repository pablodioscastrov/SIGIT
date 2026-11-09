package com.inventario.multisucursal.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Único punto por el que los módulos de negocio publican señales.
 *
 * <p>Publicar aquí <b>no</b> envía nada todavía: el envío ocurre en
 * {@link EventBroadcaster}, suscrito con
 * {@code @TransactionalEventListener(AFTER_COMMIT)}. Esa indirección es el
 * mecanismo que garantiza la regla "publica después del commit, no antes" —
 * si la transacción termina en rollback, el evento sencillamente nunca se
 * emite, y nadie recibe la señal de un cambio que no ocurrió.
 *
 * <p>Corolario deliberado: un evento publicado fuera de una transacción se
 * descarta en silencio. Todos los llamadores son métodos {@code @Transactional},
 * así que no es un caso real; hacerlo "funcionar" fuera de transacción
 * debilitaría la garantía anterior.
 */
@Component
public class DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public DomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
