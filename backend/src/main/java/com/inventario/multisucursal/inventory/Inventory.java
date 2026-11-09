package com.inventario.multisucursal.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Stock materializado por producto/sucursal (docs/DOMAIN_MODEL.md, sección
 * 2.7). Deliberadamente no extiende {@code common.audit.Auditable}: el
 * modelo aprobado solo define {@code updated_at}, sin {@code created_at}/
 * {@code created_by}/{@code updated_by} — es el único dato mutable del
 * subdominio, su historial vive en {@link InventoryMovement}.
 *
 * <p>{@code version} es un contador manual (no {@code @jakarta.persistence.Version})
 * para que {@link InventoryMovementService} controle explícitamente el
 * patrón "leer, calcular, UPDATE ... WHERE version = v, reintentar" de
 * docs/CRITICAL_FLOWS.md, sección 1.2 — la actualización real de cantidad
 * ocurre siempre vía {@link InventoryRepository#applyQuantity}, nunca
 * reasignando el campo aquí y guardando la entidad completa.
 */
@Entity
@Table(name = "inventory", uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "branch_id"}))
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "quantity_on_hand", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantityOnHand;

    @Column(name = "average_unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal averageUnitCost;

    @Column(name = "minimum_stock", nullable = false, precision = 19, scale = 6)
    private BigDecimal minimumStock;

    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Inventory() {
        // JPA
    }

    public Inventory(Long productId, Long branchId) {
        this(productId, branchId, BigDecimal.ZERO);
    }

    /**
     * {@code minimumStock} inicial tomado de {@code Product.minimumStock}
     * (BR-010, ajuste aprobado: el producto define el mínimo por defecto que
     * recibe cada sucursal la primera vez que registra stock de él). Sigue
     * sin existir un endpoint que edite este valor después de creada la fila
     * — limitación conocida, documentada en docs/STATUS.md.
     */
    public Inventory(Long productId, Long branchId, BigDecimal minimumStock) {
        this.productId = productId;
        this.branchId = branchId;
        this.quantityOnHand = BigDecimal.ZERO;
        this.averageUnitCost = BigDecimal.ZERO;
        this.minimumStock = minimumStock;
        this.version = 0L;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getBranchId() {
        return branchId;
    }

    public BigDecimal getQuantityOnHand() {
        return quantityOnHand;
    }

    public BigDecimal getAverageUnitCost() {
        return averageUnitCost;
    }

    public BigDecimal getMinimumStock() {
        return minimumStock;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
