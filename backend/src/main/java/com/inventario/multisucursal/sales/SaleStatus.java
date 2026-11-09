package com.inventario.multisucursal.sales;

/**
 * docs/DOMAIN_MODEL.md, sección 2.15, lista {@code CONFIRMED}/{@code VOIDED},
 * pero la anulación (`VOIDED`) sigue como decisión de aprobación pendiente
 * (sección "Decisiones que requieren aprobación", punto 9) — no se modela
 * aquí para no presentar como resuelta una decisión que no lo está. Este
 * enum, y por lo tanto {@code Sale}, solo admite el único estado ya
 * aprobado; {@code POST /sales/{id}/void} no se implementa en esta fase
 * (docs/API_DESIGN.md, sección 7.8, lo marca explícitamente condicionado a
 * esa aprobación).
 */
public enum SaleStatus {
    CONFIRMED
}
