package com.inventario.multisucursal.suppliers;

import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.common.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RF-012: proveedores, ciclo mínimo necesario para soportar órdenes de compra. */
@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;

    public SupplierService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    @Transactional
    public SupplierResponse create(CreateSupplierRequest request) {
        if (supplierRepository.existsByTaxId(request.taxId())) {
            throw new ResourceConflictException("IDENTIFICACION_FISCAL_YA_EXISTE", "Ya existe un proveedor con esa identificación fiscal.");
        }
        Supplier supplier = supplierRepository.save(
                new Supplier(request.name(), request.taxId(), request.contactName(), request.phone(), request.email()));
        return SupplierResponse.from(supplier);
    }

    public SupplierResponse getById(Long id) {
        return SupplierResponse.from(findOrThrow(id));
    }

    public PageResponse<SupplierResponse> list(String search, Boolean active, Pageable pageable) {
        return PageResponse.from(supplierRepository.search(search, active, pageable).map(SupplierResponse::from));
    }

    @Transactional
    public SupplierResponse update(Long id, UpdateSupplierRequest request) {
        Supplier supplier = findOrThrow(id);
        supplier.updateDetails(request.name(), request.contactName(), request.phone(), request.email());
        return SupplierResponse.from(supplier);
    }

    @Transactional
    public SupplierResponse activate(Long id) {
        Supplier supplier = findOrThrow(id);
        supplier.activate();
        return SupplierResponse.from(supplier);
    }

    @Transactional
    public SupplierResponse deactivate(Long id) {
        Supplier supplier = findOrThrow(id);
        supplier.deactivate();
        return SupplierResponse.from(supplier);
    }

    /**
     * Eliminación real (no reversible), a diferencia de activar/desactivar
     * (BR-049, misma decisión ya tomada para {@code branches}/{@code users}
     * en BR-046). {@code suppliers} no tiene ningún hijo propio que
     * comprobar con una consulta directa —a diferencia de
     * {@code BranchService.delete} con {@code users}— así que la única
     * defensa es la FK {@code ON DELETE RESTRICT} que {@code purchase_order}
     * ya declara hacia {@code supplier}: se confía en que PostgreSQL rechace
     * el `DELETE` y se traduce aquí a un conflicto legible, sin que
     * {@code suppliers} pase a depender de {@code purchases} solo para esto
     * (invertiría el grafo de dependencias, docs/ARCHITECTURE.md sección 4).
     * Esta vía solo se ejerce contra PostgreSQL real — Hibernate no genera
     * esa FK en el esquema de pruebas H2 (el modelo no usa asociaciones JPA).
     */
    @Transactional
    public void delete(Long id) {
        Supplier supplier = findOrThrow(id);
        try {
            supplierRepository.delete(supplier);
            supplierRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ResourceConflictException(
                    "PROVEEDOR_CON_DATOS_ASOCIADOS",
                    "No se puede eliminar el proveedor: tiene órdenes de compra asociadas. Desactívalo en su lugar.");
        }
    }

    private Supplier findOrThrow(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PROVEEDOR_NO_ENCONTRADO", "Proveedor no encontrado."));
    }
}
