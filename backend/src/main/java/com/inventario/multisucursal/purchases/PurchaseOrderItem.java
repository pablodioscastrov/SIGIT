package com.inventario.multisucursal.purchases;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Línea de orden de compra (docs/DOMAIN_MODEL.md, sección 2.12; RF-013,
 * RF-014, RF-016). {@code unitPrice}/{@code discountPercentage}/
 * {@code lineTotal} son la condición comercial pactada al ordenar —
 * inmutables una vez creada la línea, distintos del precio que se declara
 * en cada recepción (ver {@link com.inventario.multisucursal.inventory.InventoryMovementService}
 * y {@code PurchaseReceiptService}, que solo usan ese precio para recalcular
 * el costo promedio ponderado de {@code Inventory}, BR-004).
 *
 * <p>{@code version} es un contador manual (no {@code @jakarta.persistence.Version}),
 * igual que {@code Inventory.version} — resuelve la decisión pendiente #4 de
 * docs/BUSINESS_RULES.md: necesaria porque {@code quantityReceived} puede
 * incrementarse en varias recepciones parciales concurrentes sobre la misma
 * línea (docs/CRITICAL_FLOWS.md, flujo B).
 */
@Entity
@Table(name = "purchase_order_item")
public class PurchaseOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_order_id", nullable = false)
    private Long purchaseOrderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "unit_of_measure_id", nullable = false)
    private Long unitOfMeasureId;

    @Column(name = "quantity_ordered", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantityOrdered;

    @Column(name = "quantity_received", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantityReceived;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "discount_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal;

    @Column(nullable = false)
    private Long version;

    protected PurchaseOrderItem() {
        // JPA
    }

    public PurchaseOrderItem(
            Long purchaseOrderId,
            Long productId,
            Long unitOfMeasureId,
            BigDecimal quantityOrdered,
            BigDecimal unitPrice,
            BigDecimal discountPercentage,
            BigDecimal lineTotal) {
        this.purchaseOrderId = purchaseOrderId;
        this.productId = productId;
        this.unitOfMeasureId = unitOfMeasureId;
        this.quantityOrdered = quantityOrdered;
        this.quantityReceived = BigDecimal.ZERO;
        this.unitPrice = unitPrice;
        this.discountPercentage = discountPercentage;
        this.lineTotal = lineTotal;
        this.version = 0L;
    }

    public BigDecimal pending() {
        return quantityOrdered.subtract(quantityReceived);
    }

    public Long getId() {
        return id;
    }

    public Long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getUnitOfMeasureId() {
        return unitOfMeasureId;
    }

    public BigDecimal getQuantityOrdered() {
        return quantityOrdered;
    }

    public BigDecimal getQuantityReceived() {
        return quantityReceived;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getDiscountPercentage() {
        return discountPercentage;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public Long getVersion() {
        return version;
    }
}
