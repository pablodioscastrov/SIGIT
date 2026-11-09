package com.inventario.multisucursal.transfers;

/**
 * Máquina de estados aprobada (docs/DOMAIN_MODEL.md, sección 4;
 * docs/CRITICAL_FLOWS.md, flujos C–F; BR-020):
 *
 * <pre>
 * REQUESTED → APPROVED → IN_TRANSIT → RECEIVED_COMPLETE
 *                                   → RECEIVED_PARTIAL → CLOSED
 * REQUESTED → REJECTED
 * </pre>
 *
 * Ninguna otra transición es válida, incluido cualquier intento de
 * retroceder (BR-020). Cada transición se aplica con una comparación y
 * escritura atómica (`UPDATE ... WHERE status = <esperado>`) en
 * {@link TransferRepository}, no con una comprobación en memoria — así dos
 * solicitudes concurrentes sobre la misma transferencia no pueden aplicar
 * ambas su efecto (idempotencia de categoría 1, docs/CRITICAL_FLOWS.md
 * sección 1.1).
 */
public enum TransferStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    IN_TRANSIT,
    RECEIVED_COMPLETE,
    RECEIVED_PARTIAL,
    CLOSED
}
