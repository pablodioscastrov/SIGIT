package com.inventario.multisucursal.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Alerta de stock mínimo (docs/DOMAIN_MODEL.md, sección 2.9; BR-010; UC-16).
 *
 * <p>Sin usuario responsable a propósito (a diferencia de {@link InventoryMovement}):
 * se genera automáticamente por el sistema, no por una acción manual —
 * {@code docs/DOMAIN_MODEL.md}, sección de auditoría, lo señala explícitamente.
 *
 * <p>Solo dos estados y una transición (`ACTIVE` → `RESOLVED`): una caída de
 * stock posterior a una resolución crea una fila nueva, nunca reabre la
 * anterior — igual criterio de "no se edita/reabre historial" que el resto
 * del dominio (BR-021). La resolución se aplica con un `UPDATE` atómico
 * (ver {@link StockAlertRepository#resolveActive}), nunca mutando esta
 * entidad tras leerla, para no repetir la clase de bug ya corregida en otros
 * módulos (entidad quedando `detached` después de un `UPDATE` con
 * {@code clearAutomatically = true} en la misma transacción).
 */
@Entity
@Table(name = "stock_alert")
public class StockAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inventory_id", nullable = false)
    private Long inventoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockAlertStatus status;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected StockAlert() {
        // JPA
    }

    public StockAlert(Long inventoryId) {
        this.inventoryId = inventoryId;
        this.status = StockAlertStatus.ACTIVE;
        this.triggeredAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getInventoryId() {
        return inventoryId;
    }

    public StockAlertStatus getStatus() {
        return status;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
