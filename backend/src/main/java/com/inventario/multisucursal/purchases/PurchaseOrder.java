package com.inventario.multisucursal.purchases;

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
 * Orden de compra (docs/DOMAIN_MODEL.md, sección 2.11; RF-012, RF-013). El
 * encabezado (proveedor, sucursal, fecha) no cambia una vez creado; solo
 * {@code status} avanza, por eso no extiende {@code Auditable} — no hay
 * edición genérica que auditar, solo transiciones explícitas modeladas aquí.
 */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, length = 30)
    private String orderNumber;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status;

    @Column(name = "payment_term", length = 100)
    private String paymentTerm;

    @Column(name = "order_date", nullable = false)
    private Instant orderDate;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PurchaseOrder() {
        // JPA
    }

    public PurchaseOrder(String orderNumber, Long supplierId, Long branchId, String paymentTerm, Long createdByUserId) {
        this.orderNumber = orderNumber;
        this.supplierId = supplierId;
        this.branchId = branchId;
        this.status = PurchaseOrderStatus.CREATED;
        this.paymentTerm = paymentTerm;
        this.orderDate = Instant.now();
        this.createdByUserId = createdByUserId;
        this.createdAt = Instant.now();
    }

    public void updateStatus(PurchaseOrderStatus status) {
        this.status = status;
    }

    public void cancel() {
        this.status = PurchaseOrderStatus.CANCELLED;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Long getSupplierId() {
        return supplierId;
    }

    public Long getBranchId() {
        return branchId;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public String getPaymentTerm() {
        return paymentTerm;
    }

    public Instant getOrderDate() {
        return orderDate;
    }

    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
