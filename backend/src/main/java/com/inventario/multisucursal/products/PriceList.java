package com.inventario.multisucursal.products;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Lista de precios (docs/DOMAIN_MODEL.md, sección 2.13; RF-020). Pertenece
 * al módulo {@code products} por decisión de arquitectura (docs/ARCHITECTURE.md,
 * sección 3: "products: Catálogo de productos, unidades de medida, listas
 * de precios"), no a {@code sales} — {@code sales} solo la consulta.
 *
 * <p>{@code branchId} nulo significa lista global. No expone endpoint de
 * activar/desactivar en esta fase (fuera del contrato de docs/API_DESIGN.md,
 * sección 7.8, que solo define {@code GET}/{@code POST}) — {@code active}
 * existe para el filtro de listado y para BR-019 ("de una PriceList
 * activa"), siempre {@code true} al crearse.
 */
@Entity
@Table(name = "price_list", uniqueConstraints = @UniqueConstraint(columnNames = {"name", "branch_id"}))
public class PriceList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(nullable = false)
    private boolean active = true;

    protected PriceList() {
        // JPA
    }

    public PriceList(String name, Long branchId) {
        this.name = name;
        this.branchId = branchId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getBranchId() {
        return branchId;
    }

    public boolean isActive() {
        return active;
    }
}
