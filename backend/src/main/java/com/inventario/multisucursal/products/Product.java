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
 * Producto (docs/DOMAIN_MODEL.md, sección 2.5; RF-005). No tiene cantidad ni
 * costo — eso sigue perteneciendo a {@code Inventory}, que es por sucursal
 * (un producto no tiene "un" stock, tiene uno por cada sucursal donde se
 * mueve). {@code minimumStock} es la única excepción, y es deliberada: no es
 * una cantidad de stock, es el <b>valor por defecto</b> que recibe el mínimo
 * de una sucursal la primera vez que esa sucursal registra movimiento de
 * este producto ({@code InventoryMovementService}/{@code PurchaseReceiptService}/
 * {@code TransferService}, ver {@code findOrCreateInventory}) — no lo
 * sobrescribe si la fila de `Inventory` ya existe. Ajuste aprobado sobre la
 * condición de parada original de esta entidad ("no implementes stock dentro
 * de Product"), para que crear un producto pueda pedir de una vez el umbral
 * que alimentará el estado de reabastecimiento y las alertas de stock mínimo
 * (BR-010), sin depender de un ajuste manual posterior por cada sucursal.
 *
 * <p>{@code sku} es la clave de negocio, inmutable después de creado —
 * igual que {@code Branch.code} (docs/API_DESIGN.md, sección 7.4: el PATCH
 * actualiza "nombre/descripción", no el SKU). {@code minimumStock} sí se
 * edita después de creado (BR-059, por instrucción explícita, revierte la
 * inmutabilidad original de BR-048): sigue siendo solo el valor de siembra
 * para la primera vez que cada sucursal registra movimiento de este
 * producto — editarlo no toca ninguna fila de {@code Inventory} ya
 * materializada, solo cambia qué valor recibirán las sucursales que aún no
 * lo tengan.
 */
@Entity
@Table(name = "product")
public class Product extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String sku;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "base_unit_of_measure_id", nullable = false)
    private Long baseUnitOfMeasureId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "minimum_stock", nullable = false, precision = 19, scale = 6)
    private BigDecimal minimumStock;

    protected Product() {
        // JPA
    }

    /** Sin {@code minimumStock} explícito: queda en 0, igual que el valor por defecto histórico de {@code Inventory.minimum_stock}. */
    public Product(String sku, String name, String description, Long baseUnitOfMeasureId) {
        this(sku, name, description, baseUnitOfMeasureId, BigDecimal.ZERO);
    }

    public Product(String sku, String name, String description, Long baseUnitOfMeasureId, BigDecimal minimumStock) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.baseUnitOfMeasureId = baseUnitOfMeasureId;
        this.minimumStock = minimumStock;
    }

    public void updateDetails(String name, String description, BigDecimal minimumStock) {
        this.name = name;
        this.description = description;
        this.minimumStock = minimumStock;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Long getBaseUnitOfMeasureId() {
        return baseUnitOfMeasureId;
    }

    public boolean isActive() {
        return active;
    }

    public BigDecimal getMinimumStock() {
        return minimumStock;
    }
}
