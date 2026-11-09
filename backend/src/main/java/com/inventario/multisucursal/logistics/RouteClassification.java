package com.inventario.multisucursal.logistics;

/**
 * Criterio por el que se clasifica una ruta (RF-028; docs/DOMAIN_MODEL.md,
 * sección 2.17). Una sola etiqueta por ruta — ver la justificación en la
 * migración V24 y en BR-037.
 */
public enum RouteClassification {
    /** La ruta se atiende por urgencia, aunque cueste más o tarde más. */
    PRIORITY,
    /** La ruta se optimiza por costo de traslado. */
    COST,
    /** La ruta se optimiza por tiempo de entrega. */
    TIME
}
