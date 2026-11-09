package com.inventario.multisucursal.inventory;

/** BR-010: una alerta solo transita ACTIVE → RESOLVED, nunca al revés (ver {@link StockAlert}). */
public enum StockAlertStatus {
    ACTIVE,
    RESOLVED
}
