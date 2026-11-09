package com.inventario.multisucursal.common.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Entidad exclusiva de test para verificar {@link Auditable} — no forma parte
 * del código de producción ni de ningún módulo de negocio (vive bajo
 * src/test). No representa ninguna tabla real del modelo de dominio.
 */
@Entity
public class AuditableTestEntity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
