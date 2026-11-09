package com.inventario.multisucursal.users;

import com.inventario.multisucursal.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Usuario (docs/DOMAIN_MODEL.md, sección 2.3; UC-14 en docs/USE_CASES.md).
 *
 * <p>{@code branchId} es {@code null} solo para {@code ADMIN} (alcance
 * global); para {@code MANAGER}/{@code OPERATOR} siempre debe tener valor —
 * la restricción real vive en la base de datos (`CHECK`, ver migración
 * V3__create_users_table.sql) y se revalida en {@code UserService} antes de
 * escribir (defensa en profundidad, no solo un `CHECK` silencioso).
 */
@Entity
@Table(name = "users")
public class User extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false, length = 20)
    private RoleCode roleCode;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "deactivation_reason", length = 500)
    private String deactivationReason;

    protected User() {
        // JPA
    }

    public User(String name, String email, String passwordHash, RoleCode roleCode, Long branchId) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roleCode = roleCode;
        this.branchId = branchId;
    }

    public void updateProfile(String name, String email, RoleCode roleCode, Long branchId) {
        this.name = name;
        this.email = email;
        this.roleCode = roleCode;
        this.branchId = branchId;
    }

    public void activate() {
        this.active = true;
        // El motivo describe la desactivación que se está cerrando; una vez
        // reactivado ya no aplica y no debe seguir mostrándose (UC-14).
        this.deactivationReason = null;
    }

    public void deactivate(String reason) {
        this.active = false;
        this.deactivationReason = reason;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public RoleCode getRoleCode() {
        return roleCode;
    }

    public Long getBranchId() {
        return branchId;
    }

    public boolean isActive() {
        return active;
    }

    public String getDeactivationReason() {
        return deactivationReason;
    }
}
