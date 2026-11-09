package com.inventario.multisucursal.purchases;

/** docs/DOMAIN_MODEL.md, sección 2.11: {@code CREATED → PARTIALLY_RECEIVED → RECEIVED}, o {@code CREATED → CANCELLED}. */
public enum PurchaseOrderStatus {
    CREATED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CANCELLED
}
