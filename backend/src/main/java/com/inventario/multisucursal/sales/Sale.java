package com.inventario.multisucursal.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Venta (docs/DOMAIN_MODEL.md, sección 2.15; RF-017 a RF-021). Se crea
 * directamente en {@code CONFIRMED} — no existe estado "borrador" en el
 * alcance actual (docs/CRITICAL_FLOWS.md, flujo A). {@code subtotal}/
 * {@code discountTotal}/{@code total} se fijan al confirmar y no se editan
 * después (BR-021); {@code clientReferenceId} es la clave de idempotencia
 * (categoría 2 — creación repetible, docs/CRITICAL_FLOWS.md, sección 1.1;
 * decisión pendiente #5 de docs/BUSINESS_RULES.md, resuelta al implementar
 * este módulo).
 */
@Entity
@Table(name = "sale")
public class Sale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_number", nullable = false, unique = true, length = 30)
    private String saleNumber;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "sold_by_user_id", nullable = false)
    private Long soldByUserId;

    @Column(name = "price_list_id", nullable = false)
    private Long priceListId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SaleStatus status;

    @Column(name = "sale_date", nullable = false)
    private Instant saleDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal;

    @Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountTotal;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal total;

    @Column(name = "client_reference_id", unique = true, length = 150)
    private String clientReferenceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Sale() {
        // JPA
    }

    public Sale(String saleNumber, Long branchId, Long soldByUserId, Long priceListId, String clientReferenceId) {
        this.saleNumber = saleNumber;
        this.branchId = branchId;
        this.soldByUserId = soldByUserId;
        this.priceListId = priceListId;
        this.status = SaleStatus.CONFIRMED;
        this.saleDate = Instant.now();
        this.subtotal = BigDecimal.ZERO;
        this.discountTotal = BigDecimal.ZERO;
        this.total = BigDecimal.ZERO;
        this.clientReferenceId = clientReferenceId;
        this.createdAt = Instant.now();
    }

    public void updateTotals(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal total) {
        this.subtotal = subtotal;
        this.discountTotal = discountTotal;
        this.total = total;
    }

    public Long getId() {
        return id;
    }

    public String getSaleNumber() {
        return saleNumber;
    }

    public Long getBranchId() {
        return branchId;
    }

    public Long getSoldByUserId() {
        return soldByUserId;
    }

    public Long getPriceListId() {
        return priceListId;
    }

    public SaleStatus getStatus() {
        return status;
    }

    public Instant getSaleDate() {
        return saleDate;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getClientReferenceId() {
        return clientReferenceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
