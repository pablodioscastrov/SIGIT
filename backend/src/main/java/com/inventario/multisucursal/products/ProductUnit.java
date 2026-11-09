package com.inventario.multisucursal.products;

import com.inventario.multisucursal.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Unidad alternativa de un producto, con su factor de conversión hacia la
 * unidad base (docs/DOMAIN_MODEL.md, sección 2.6; BR-011). {@code BigDecimal}
 * para el factor — nunca {@code float}/{@code double} — porque una
 * conversión imprecisa se propagaría a cada movimiento de inventario futuro
 * que use esta unidad.
 *
 * <p>La fila con {@code isBaseUnit = true} la crea automáticamente
 * {@link ProductService} al crear el producto (factor 1, por definición);
 * este constructor público es para las unidades alternativas que se agregan
 * después vía {@code POST /products/{id}/units}.
 */
@Entity
@Table(name = "product_unit")
public class ProductUnit extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "unit_of_measure_id", nullable = false)
    private Long unitOfMeasureId;

    @Column(name = "conversion_factor_to_base", nullable = false, precision = 19, scale = 6)
    private BigDecimal conversionFactorToBase;

    @Column(name = "is_base_unit", nullable = false)
    private boolean baseUnit;

    protected ProductUnit() {
        // JPA
    }

    public ProductUnit(Long productId, Long unitOfMeasureId, BigDecimal conversionFactorToBase, boolean baseUnit) {
        this.productId = productId;
        this.unitOfMeasureId = unitOfMeasureId;
        this.conversionFactorToBase = conversionFactorToBase;
        this.baseUnit = baseUnit;
    }

    public void updateConversionFactor(BigDecimal conversionFactorToBase) {
        this.conversionFactorToBase = conversionFactorToBase;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getUnitOfMeasureId() {
        return unitOfMeasureId;
    }

    public BigDecimal getConversionFactorToBase() {
        return conversionFactorToBase;
    }

    public boolean isBaseUnit() {
        return baseUnit;
    }
}
