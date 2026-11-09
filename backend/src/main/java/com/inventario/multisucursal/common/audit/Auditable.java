package com.inventario.multisucursal.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Columnas de auditoría base para entidades que representan un hecho de
 * negocio con historial de creación/modificación (docs/DOMAIN_MODEL.md,
 * sección 1). No aplica a tablas de solo-inserción como {@code InventoryMovement}
 * (docs/adr/ADR-008-trazabilidad-inventory-movement.md), que ya tienen su
 * propio {@code responsible_user_id}/{@code occurred_at} específico del
 * negocio — esta clase es para entidades de referencia/documento que sí
 * pueden actualizarse (p. ej. Branch, Product, PurchaseOrder).
 *
 * <p>Sin setters: estos campos los gestiona exclusivamente
 * {@link AuditingEntityListener}, nunca código de aplicación.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
