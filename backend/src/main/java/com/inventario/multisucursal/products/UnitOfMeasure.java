package com.inventario.multisucursal.products;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Catálogo de unidades de medida (docs/DOMAIN_MODEL.md, sección 2.4; RF-011).
 * Sin columnas de auditoría ni de estado: no hay baja lógica. Sí admite
 * editar {@code name} (BR-050, ampliación explícita del contrato original
 * "solo GET/POST") — {@code code} es la clave de negocio y permanece
 * inmutable después de creado, mismo criterio que {@code Product.sku}.
 */
@Entity
@Table(name = "unit_of_measure")
public class UnitOfMeasure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    protected UnitOfMeasure() {
        // JPA
    }

    public UnitOfMeasure(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public void updateDetails(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
