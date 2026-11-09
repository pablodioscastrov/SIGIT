package com.inventario.multisucursal.suppliers;

import com.inventario.multisucursal.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Proveedor (docs/DOMAIN_MODEL.md, sección 2.10; RF-012). {@code taxId} es la
 * clave de negocio (identificación fiscal), inmutable después de creado —
 * igual que {@code Product.sku}/{@code Branch.code}.
 */
@Entity
@Table(name = "supplier")
public class Supplier extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "tax_id", nullable = false, unique = true, length = 50)
    private String taxId;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(length = 30)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(nullable = false)
    private boolean active = true;

    protected Supplier() {
        // JPA
    }

    public Supplier(String name, String taxId, String contactName, String phone, String email) {
        this.name = name;
        this.taxId = taxId;
        this.contactName = contactName;
        this.phone = phone;
        this.email = email;
    }

    public void updateDetails(String name, String contactName, String phone, String email) {
        this.name = name;
        this.contactName = contactName;
        this.phone = phone;
        this.email = email;
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

    public String getName() {
        return name;
    }

    public String getTaxId() {
        return taxId;
    }

    public String getContactName() {
        return contactName;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public boolean isActive() {
        return active;
    }
}
