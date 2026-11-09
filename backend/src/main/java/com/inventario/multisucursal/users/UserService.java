package com.inventario.multisucursal.users;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.common.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-14 (docs/USE_CASES.md): crear, consultar, editar y activar/desactivar
 * usuarios, asignando rol y sucursal. Toda operación está restringida a
 * ADMIN a nivel de {@link UserController} (docs/API_DESIGN.md, sección 6) —
 * este servicio no vuelve a comprobar el rol de quien llama, solo las reglas
 * de negocio propias del recurso (consistencia rol/sucursal, unicidad de
 * email, existencia y estado de la sucursal referenciada).
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, BranchRepository branchRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.branchRepository = branchRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        validateRoleBranchConsistency(request.role(), request.branchId());
        if (request.branchId() != null) {
            requireActiveBranch(request.branchId());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ResourceConflictException("EMAIL_YA_EXISTE", "Ya existe un usuario con ese correo.");
        }

        User user = new User(
                request.name(),
                request.email(),
                passwordEncoder.encode(request.password()),
                request.role(),
                request.branchId());
        return UserResponse.from(userRepository.save(user));
    }

    public UserResponse getById(Long id) {
        return UserResponse.from(findOrThrow(id));
    }

    public PageResponse<UserResponse> list(Long branchId, RoleCode role, Boolean active, Pageable pageable) {
        return PageResponse.from(userRepository.search(branchId, role, active, pageable).map(UserResponse::from));
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = findOrThrow(id);
        validateRoleBranchConsistency(request.role(), request.branchId());
        if (request.branchId() != null) {
            requireActiveBranch(request.branchId());
        }
        if (userRepository.existsByEmailAndIdNot(request.email(), id)) {
            throw new ResourceConflictException("EMAIL_YA_EXISTE", "Ya existe un usuario con ese correo.");
        }
        user.updateProfile(request.name(), request.email(), request.role(), request.branchId());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse activate(Long id) {
        User user = findOrThrow(id);
        user.activate();
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse deactivate(Long id, String reason) {
        requireNotSelf(id, "desactivar");
        User user = findOrThrow(id);
        user.deactivate(reason);
        return UserResponse.from(user);
    }

    /**
     * Eliminación real (no reversible), a diferencia de activar/desactivar.
     * Se apoya en las FK {@code ON DELETE RESTRICT} hacia {@code users}
     * (movimientos, compras, ventas, transferencias) que ya declara el
     * esquema: si el usuario tiene cualquier historial asociado, PostgreSQL
     * rechaza el `DELETE` y se traduce a un conflicto legible en vez de
     * dejar que la excepción cruda llegue al cliente.
     *
     * <p>No hay una comprobación explícita equivalente aquí porque
     * {@code users} no depende de {@code inventory}/{@code purchases}/
     * {@code sales}/{@code transfers} (y no debe empezar a hacerlo solo para
     * esto — invertiría el grafo de dependencias, docs/ARCHITECTURE.md
     * sección 4): la única vía de protección es la FK. <b>Por eso mismo solo
     * se ejerce contra PostgreSQL real</b> (Hibernate no genera esas FK en el
     * esquema de pruebas H2, el modelo no usa asociaciones JPA) — verificado
     * en vivo, no por la suite basada en H2, igual que
     * {@code FlywayMigrationIntegrationTest}.
     */
    @Transactional
    public void delete(Long id) {
        requireNotSelf(id, "eliminar");
        User user = findOrThrow(id);
        try {
            userRepository.delete(user);
            userRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ResourceConflictException(
                    "USUARIO_CON_DATOS_ASOCIADOS",
                    "No se puede eliminar el usuario: tiene movimientos, compras, ventas o transferencias asociadas. Desactívalo en su lugar.");
        }
    }

    private void requireNotSelf(Long targetId, String action) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedUser current && current.userId().equals(targetId)) {
            throw new BusinessRuleViolationException("NO_AUTOGESTION", "No puedes " + action + " tu propia cuenta.");
        }
    }

    private void validateRoleBranchConsistency(RoleCode role, Long branchId) {
        // Refleja en la capa de aplicación el mismo CHECK de base de datos
        // (V3__create_users_table.sql) - defensa en profundidad, no solo un
        // rechazo silencioso a nivel de SQL.
        if (role == RoleCode.ADMIN && branchId != null) {
            throw new BusinessRuleViolationException(
                    "ADMIN_SIN_SUCURSAL", "Un administrador general no debe tener sucursal asignada.");
        }
        if (role != RoleCode.ADMIN && branchId == null) {
            throw new BusinessRuleViolationException(
                    "SUCURSAL_REQUERIDA", "Este rol requiere una sucursal asignada.");
        }
    }

    private void requireActiveBranch(Long branchId) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("SUCURSAL_NO_ENCONTRADA", "Sucursal no encontrada."));
        if (!branch.isActive()) {
            throw new BusinessRuleViolationException("SUCURSAL_INACTIVA", "No se puede asignar un usuario a una sucursal inactiva.");
        }
    }

    private User findOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("USUARIO_NO_ENCONTRADO", "Usuario no encontrado."));
    }
}
