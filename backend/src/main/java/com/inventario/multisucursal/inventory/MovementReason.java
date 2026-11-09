package com.inventario.multisucursal.inventory;

/**
 * Catálogo completo de motivos (docs/DOMAIN_MODEL.md, sección 2.8). El
 * ajuste manual ({@link InventoryMovementService}) solo permite escribir con
 * {@link #DEVOLUCION}, {@link #AJUSTE_INGRESO}, {@link #MERMA} o
 * {@link #AJUSTE_RETIRO}, validado explícitamente ahí. {@link #DEVOLUCION}
 * también la produce ahora {@code sales.SaleReturnService} (BR-052) —
 * enlazada a la línea de venta de origen vía {@code sale_item_id}, a
 * diferencia del ajuste manual, que no tiene ningún documento de origen.
 * {@link #COMPRA} la genera `purchases`, {@link #VENTA} y
 * {@link #TRANSFERENCIA_SALIDA}/{@link #TRANSFERENCIA_ENTRADA} las generan
 * `sales`/`transfers` respectivamente.
 */
public enum MovementReason {
    COMPRA,
    DEVOLUCION,
    AJUSTE_INGRESO,
    VENTA,
    MERMA,
    AJUSTE_RETIRO,
    TRANSFERENCIA_SALIDA,
    TRANSFERENCIA_ENTRADA
}
