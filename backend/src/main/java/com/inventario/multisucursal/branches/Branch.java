package com.inventario.multisucursal.branches;

import com.inventario.multisucursal.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Sucursal (docs/DOMAIN_MODEL.md, sección 2.1; UC-15 en docs/USE_CASES.md:
 * "crea, consulta o edita los datos de una sucursal — nombre, ubicación,
 * estado activo/inactivo"). {@code code} es la clave de negocio, inmutable
 * después de creada — no forma parte de la actualización (docs/API_DESIGN.md,
 * sección 7.3: el PATCH actualiza "datos", no el código).
 */
@Entity
@Table(name = "branch")
public class Branch extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 255)
    private String location;

    @Column(nullable = false)
    private boolean active = true;

    protected Branch() {
        // JPA
    }

    public Branch(String code, String name) {
        this(code, name, null);
    }

    public Branch(String code, String name, String location) {
        this.code = code;
        this.name = name;
        this.location = location;
    }

    public void updateDetails(String name, String location) {
        this.name = name;
        this.location = location;
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

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    public boolean isActive() {
        return active;
    }
}
