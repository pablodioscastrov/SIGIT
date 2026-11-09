package com.inventario.multisucursal.branches;

import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.common.web.PageResponse;
import com.inventario.multisucursal.users.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-15 (docs/USE_CASES.md): crear, consultar, editar y activar/desactivar
 * sucursales. Toda escritura está restringida a ADMIN a nivel de
 * {@link BranchController} (docs/API_DESIGN.md, sección 6) — este servicio
 * no vuelve a comprobar el rol, solo las reglas de negocio propias del
 * recurso.
 */
@Service
public class BranchService {

    private final BranchRepository branchRepository;
    private final UserRepository userRepository;

    public BranchService(BranchRepository branchRepository, UserRepository userRepository) {
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public BranchResponse create(CreateBranchRequest request) {
        if (branchRepository.existsByCode(request.code())) {
            throw new ResourceConflictException("CODIGO_YA_EXISTE", "Ya existe una sucursal con ese código.");
        }
        Branch branch = new Branch(request.code(), request.name(), request.location());
        return BranchResponse.from(branchRepository.save(branch));
    }

    public BranchResponse getById(Long id) {
        return BranchResponse.from(findOrThrow(id));
    }

    public PageResponse<BranchResponse> list(Boolean active, Pageable pageable) {
        return PageResponse.from(branchRepository.search(active, pageable).map(BranchResponse::from));
    }

    @Transactional
    public BranchResponse update(Long id, UpdateBranchRequest request) {
        Branch branch = findOrThrow(id);
        branch.updateDetails(request.name(), request.location());
        return BranchResponse.from(branch);
    }

    @Transactional
    public BranchResponse activate(Long id) {
        Branch branch = findOrThrow(id);
        branch.activate();
        return BranchResponse.from(branch);
    }

    @Transactional
    public BranchResponse deactivate(Long id) {
        Branch branch = findOrThrow(id);
        // UC-15, flujo alterno 1a: no desactivar una sucursal con usuarios
        // activos asignados - los dejaría apuntando a una sucursal inactiva,
        // una inconsistencia equivalente a la que la regla busca evitar para
        // inventario/transferencias en módulos todavía no implementados.
        if (userRepository.existsByBranchIdAndActiveTrue(id)) {
            throw new ResourceConflictException(
                    "SUCURSAL_CON_USUARIOS_ACTIVOS",
                    "No se puede desactivar la sucursal: todavía tiene usuarios activos asignados.");
        }
        branch.deactivate();
        return BranchResponse.from(branch);
    }

    /**
     * Eliminación real (no reversible), a diferencia de activar/desactivar.
     *
     * <p>La comprobación de usuarios asignados es explícita porque
     * {@code users} ya es una dependencia legítima de este módulo (igual que
     * en {@link #deactivate}); el resto de referencias posibles —inventario,
     * compras, ventas, transferencias, rutas— viven en módulos que
     * {@code branches} no debe pasar a depender solo para esto (invertiría el
     * grafo de dependencias, docs/ARCHITECTURE.md sección 4). Para esas se
     * confía en las FK {@code ON DELETE RESTRICT} hacia {@code branch} que ya
     * declara el esquema: PostgreSQL rechaza el `DELETE` y se traduce aquí a
     * un conflicto legible. <b>Esta segunda vía solo se ejerce contra
     * PostgreSQL real</b> (Hibernate no genera esas FK en el esquema de
     * pruebas H2 porque el modelo no usa asociaciones JPA, docs/DECISIONS.md)
     * — verificada en vivo, no por la suite basada en H2, igual que
     * {@code FlywayMigrationIntegrationTest}.
     */
    @Transactional
    public void delete(Long id) {
        Branch branch = findOrThrow(id);
        if (userRepository.existsByBranchId(id)) {
            throw conflict();
        }
        try {
            branchRepository.delete(branch);
            branchRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw conflict();
        }
    }

    private ResourceConflictException conflict() {
        return new ResourceConflictException(
                "SUCURSAL_CON_DATOS_ASOCIADOS",
                "No se puede eliminar la sucursal: tiene usuarios, inventario, compras, ventas, transferencias o rutas asociadas. Desactívala en su lugar.");
    }

    private Branch findOrThrow(Long id) {
        return branchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SUCURSAL_NO_ENCONTRADA", "Sucursal no encontrada."));
    }
}
