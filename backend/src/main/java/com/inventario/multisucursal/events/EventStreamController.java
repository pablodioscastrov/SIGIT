package com.inventario.multisucursal.events;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.auth.AuthorizationService;
import com.inventario.multisucursal.common.exception.BranchAccessDeniedException;
import com.inventario.multisucursal.users.RoleCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canal near-real-time (docs/API_DESIGN.md, sección 7.11; ADR-007).
 *
 * <p>Es el <b>único</b> endpoint de la API que no responde a una petición
 * puntual: mantiene la conexión abierta y empuja señales. Todo lo demás
 * —consultar, vender, aprobar— sigue siendo REST síncrono
 * (docs/ARCHITECTURE.md, sección 9: "cuándo NO se usa SSE").
 *
 * <p>Cuando el emisor expira, Spring cierra la conexión y el navegador
 * reconecta solo. No se reenvían los eventos perdidos entre medias, y es
 * deliberado: el evento es una señal, no un dato, así que el cliente se
 * reconcilia consultando REST — que es la fuente de verdad — en vez de
 * depender de un búfer de reproducción que sería una segunda fuente.
 */
@RestController
@RequestMapping("/api/v1")
public class EventStreamController {

    private static final Logger log = LoggerFactory.getLogger(EventStreamController.class);

    /** Vida máxima de una conexión; al expirar, {@code EventSource} reconecta por su cuenta. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);
    /** Sugerencia de espera antes de reconectar, enviada al cliente en el campo {@code retry}. */
    private static final Duration RECONNECT_HINT = Duration.ofSeconds(3);

    private final EventBroadcaster eventBroadcaster;
    private final AuthorizationService authorizationService;

    public EventStreamController(EventBroadcaster eventBroadcaster, AuthorizationService authorizationService) {
        this.eventBroadcaster = eventBroadcaster;
        this.authorizationService = authorizationService;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(name = "branchId", required = false) List<Long> branchIds,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        Set<Long> branchFilter = branchIds == null ? Set.of() : new HashSet<>(branchIds);
        // Un no-ADMIN no puede pedir seguir una sucursal ajena: se rechaza al
        // suscribirse, en vez de aceptar la suscripción y filtrar en silencio.
        if (principal.role() != RoleCode.ADMIN) {
            for (Long branchId : branchFilter) {
                if (!branchId.equals(principal.branchId())) {
                    throw new BranchAccessDeniedException("No puede suscribirse a eventos de una sucursal ajena.");
                }
            }
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        long subscriptionId = eventBroadcaster.register(principal, branchFilter, emitter);

        try {
            // Confirma al cliente que el canal quedó abierto y le sugiere el
            // intervalo de reconexión. Si esto falla, la conexión no servía.
            emitter.send(SseEmitter.event()
                    .name("stream.opened")
                    .reconnectTime(RECONNECT_HINT.toMillis())
                    .data(Map.of("subscribedBranches", branchFilter.stream().map(String::valueOf).sorted().toList())));
        } catch (IOException openFailure) {
            log.debug("No se pudo abrir el canal SSE {}: {}", subscriptionId, openFailure.toString());
            eventBroadcaster.unregister(subscriptionId);
            emitter.completeWithError(openFailure);
        }

        return emitter;
    }
}
