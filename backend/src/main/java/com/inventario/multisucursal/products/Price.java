package com.inventario.multisucursal.products;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Precio versionado (docs/DOMAIN_MODEL.md, sección 2.14, decisión 3.4).
 * Ninguna fila se edita ni se elimina — "cambiar un precio" cierra la
 * vigente ({@code validTo}) e inserta una nueva (BR-019). Vigente cuando
 * {@code validTo IS NULL}.
 */
@Entity
@Table(name = "price")
public class Price {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "price_list_id", nullable = false)
    private Long priceListId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    protected Price() {
        // JPA
    }

    public Price(Long priceListId, Long productId, BigDecimal unitPrice) {
        this.priceListId = priceListId;
        this.productId = productId;
        this.unitPrice = unitPrice;
        this.validFrom = Instant.now();
    }

    public void close() {
        this.validTo = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPriceListId() {
        return priceListId;
    }

    public Long getProductId() {
        return productId;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }
}
