package com.inventario.multisucursal.events;

import java.time.Instant;
import java.util.Set;

/**
 * Señal de que algo cambió — <b>no</b> el dato de negocio (ADR-007;
 * docs/ARCHITECTURE.md, sección 9). Quien la recibe vuelve a consultar la API
 * REST para obtener el valor autoritativo. Por eso el evento lleva solo el
 * tipo, las sucursales implicadas y el id del recurso: si transportara el
 * payload completo, habría dos fuentes de verdad que podrían discrepar.
 *
 * @param branchIds        sucursales implicadas — de ellas depende quién puede recibirlo
 * @param branchRestricted si el evento solo es visible para quien pertenece a
 *        alguna de esas sucursales. {@code false} para inventario, cuya lectura
 *        es abierta a cualquier sucursal por RF-003: la señal no revela nada
 *        que el usuario no pudiera consultar ya por REST. {@code true} para
 *        transferencias, cuya lectura sí está acotada a origen/destino
 *        (docs/API_DESIGN.md, sección 6). El canal concede exactamente lo
 *        mismo que concede la API, ni más ni menos.
 */
public record DomainEvent(
        String type,
        Set<Long> branchIds,
        String resourceId,
        boolean branchRestricted,
        Instant occurredAt) {

    public static final String INVENTORY_UPDATED = "inventory.updated";
    public static final String STOCK_ALERT_TRIGGERED = "stock-alert.triggered";
    public static final String STOCK_ALERT_RESOLVED = "stock-alert.resolved";
    public static final String TRANSFER_STATUS_CHANGED = "transfer.status-changed";
    public static final String TRANSFER_DISCREPANCY_OPENED = "transfer.discrepancy-opened";
    /** Venta confirmada: documento completo disponible para el Data Lake. */
    public static final String SALE_CONFIRMED = "sale.confirmed";
    /** Recepción de compra registrada (total o parcial). */
    public static final String PURCHASE_ORDER_RECEIVED = "purchase-order.received";

    /** Cambió el stock de un producto en una sucursal (RF-002, RNF-001). {@code resourceId} = producto. */
    public static DomainEvent inventoryUpdated(Long branchId, Long productId) {
        return new DomainEvent(INVENTORY_UPDATED, Set.of(branchId), String.valueOf(productId), false, Instant.now());
    }

    /**
     * Un producto en una sucursal alcanzó o cayó por debajo de su stock
     * mínimo (BR-010, UC-16). {@code resourceId} = producto. {@code
     * branchRestricted = false}: la lectura de `stock-alerts` es abierta a
     * cualquier sucursal, igual que `inventory` (RF-003) — la señal no
     * revela nada que el destinatario no pudiera ya consultar por REST.
     */
    public static DomainEvent stockAlertTriggered(Long branchId, Long productId) {
        return new DomainEvent(STOCK_ALERT_TRIGGERED, Set.of(branchId), String.valueOf(productId), false, Instant.now());
    }

    /** El stock de ese producto volvió a superar el mínimo y su alerta activa se resolvió (BR-010). */
    public static DomainEvent stockAlertResolved(Long branchId, Long productId) {
        return new DomainEvent(STOCK_ALERT_RESOLVED, Set.of(branchId), String.valueOf(productId), false, Instant.now());
    }

    /** Una transferencia avanzó de estado (RF-029). {@code resourceId} = transferencia. */
    public static DomainEvent transferStatusChanged(Long transferId, Long originBranchId, Long destinationBranchId) {
        return new DomainEvent(
                TRANSFER_STATUS_CHANGED, Set.of(originBranchId, destinationBranchId), String.valueOf(transferId), true, Instant.now());
    }

    /** Una recepción parcial dejó un faltante sin tratar (RF-026, flujo F1). */
    public static DomainEvent transferDiscrepancyOpened(Long transferId, Long originBranchId, Long destinationBranchId) {
        return new DomainEvent(
                TRANSFER_DISCREPANCY_OPENED, Set.of(originBranchId, destinationBranchId), String.valueOf(transferId), true, Instant.now());
    }

    /**
     * Venta confirmada. {@code resourceId} = id de venta. {@code branchRestricted = true}:
     * los datos de venta (precios, totales) solo son visibles para quien
     * pertenece a esa sucursal, igual que las transferencias.
     */
    public static DomainEvent saleConfirmed(Long saleId, Long branchId) {
        return new DomainEvent(SALE_CONFIRMED, Set.of(branchId), String.valueOf(saleId), true, Instant.now());
    }

    /**
     * Recepción de compra registrada (total o parcial). {@code resourceId} = id de
     * la orden de compra. {@code branchRestricted = true}: datos de costo de
     * compra restringidos a la sucursal receptora.
     */
    public static DomainEvent purchaseOrderReceived(Long purchaseOrderId, Long branchId) {
        return new DomainEvent(PURCHASE_ORDER_RECEIVED, Set.of(branchId), String.valueOf(purchaseOrderId), true, Instant.now());
    }
}
