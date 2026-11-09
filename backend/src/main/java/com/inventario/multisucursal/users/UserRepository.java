package com.inventario.multisucursal.users;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** BR-058: valida unicidad de correo al editar, sin que el propio usuario choque contra su correo actual. */
    boolean existsByEmailAndIdNot(String email, Long id);

    /**
     * Usado por {@code branches.BranchService} para impedir desactivar una
     * sucursal que todavía tiene usuarios activos asignados (UC-15, flujo
     * alterno 1a: evitar inconsistencia al retirar una sucursal en uso).
     */
    boolean existsByBranchIdAndActiveTrue(Long branchId);

    /**
     * Usado por {@code branches.BranchService} para impedir eliminar una
     * sucursal con cualquier usuario asignado, activo o no (a diferencia de
     * {@link #existsByBranchIdAndActiveTrue}: aquí ni siquiera un usuario ya
     * desactivado debe quedar apuntando a una sucursal que ya no existe).
     */
    boolean existsByBranchId(Long branchId);

    @Query("""
            SELECT u FROM User u
            WHERE (:branchId IS NULL OR u.branchId = :branchId)
              AND (:role IS NULL OR u.roleCode = :role)
              AND (:active IS NULL OR u.active = :active)
            """)
    Page<User> search(
            @Param("branchId") Long branchId,
            @Param("role") RoleCode role,
            @Param("active") Boolean active,
            Pageable pageable);
}
