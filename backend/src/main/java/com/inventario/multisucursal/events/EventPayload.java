package com.inventario.multisucursal.events;

import java.time.Instant;
import java.util.List;

/**
 * Forma en el cable. Los identificadores viajan como string, igual que en
 * toda la API REST (docs/API_DESIGN.md, sección 1) — el canal SSE no
 * introduce una convención distinta.
 */
public record EventPayload(String type, List<String> branchIds, String resourceId, Instant occurredAt) {

    public static EventPayload from(DomainEvent event) {
        return new EventPayload(
                event.type(),
                event.branchIds().stream().map(String::valueOf).sorted().toList(),
                event.resourceId(),
                event.occurredAt());
    }
}
