package com.inventario.multisucursal.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

/**
 * Línea de venta (docs/DOMAIN_MODEL.md, sección 2.16; RF-017, RF-020).
 * {@code unitPrice}/{@code discountPercentage}/{@code lineTotal} son
 * inmutables una vez creada — {@code unitPrice} es el precio efectivamente
 * cobrado (copiado de {@code Price} vigente al confirmar), no cambia si el
 * precio de referencia cambia después (BR-021). {@code quantityReturned}
 * (BR-052) es la única excepción: cantidad acumulada devuelta contra esta
 * línea, con su propio {@code version} para bloqueo optimista (igual patrón
 * que {@code PurchaseOrderItem.version}, necesario porque puede haber más de
 * una devolución parcial sobre la misma línea).
 */
@Entity
@Table(name = "sale_item", uniqueConstraints = @UniqueConstraint(columnNames = {"sale_id", "product_id"}))
public class SaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_id", nullable = false)
    private Long saleId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "unit_of_measure_id", nullable = false)
    private Long unitOfMeasureId;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "discount_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal;

    @Column(name = "quantity_returned", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantityReturned;

    @Column(nullable = false)
    private Long version;

    protected SaleItem() {
        // JPA
    }

    public SaleItem(
            Long saleId, Long productId, Long unitOfMeasureId, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal discountPercentage, BigDecimal lineTotal) {
        this.saleId = saleId;
        this.productId = productId;
        this.unitOfMeasureId = unitOfMeasureId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.discountPercentage = discountPercentage;
        this.lineTotal = lineTotal;
        this.quantityReturned = BigDecimal.ZERO;
        this.version = 0L;
    }

    /** BR-052: cantidad de esta línea todavía disponible para devolver. */
    public BigDecimal pending() {
        return quantity.subtract(quantityReturned);
    }

    public Long getId() {
        return id;
    }

    public Long getSaleId() {
        return saleId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getUnitOfMeasureId() {
        return unitOfMeasureId;
    }

    public BigDecimal getQuantity() {
        return quantity;
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

    public BigDecimal getQuantityReturned() {
        return quantityReturned;
    }

    public Long getVersion() {
        return version;
    }
}
