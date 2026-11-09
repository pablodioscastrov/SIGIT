package com.inventario.multisucursal.suppliers;

import com.inventario.multisucursal.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/API_DESIGN.md, sección 7.6. Lectura abierta a cualquier rol
 * autenticado. Escritura — por instrucción explícita, BR-058, que reduce el
 * alcance de BR-049 — ya no es uniforme: crear/editar/activar/desactivar es
 * {@code MANAGER}+{@code ADMIN} ({@code OPERATOR} pasa a solo lectura) y
 * eliminar (real, no reversible) queda exclusivo de {@code ADMIN} (ni
 * siquiera {@code MANAGER}). Sin restricción por sucursal en ningún caso: el
 * proveedor no pertenece a ninguna.
 */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    public PageResponse<SupplierResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable) {
        return supplierService.list(search, active, pageable);
    }

    @GetMapping("/{id}")
    public SupplierResponse get(@PathVariable Long id) {
        return supplierService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public SupplierResponse create(@Valid @RequestBody CreateSupplierRequest request) {
        return supplierService.create(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public SupplierResponse update(@PathVariable Long id, @Valid @RequestBody UpdateSupplierRequest request) {
        return supplierService.update(id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public SupplierResponse activate(@PathVariable Long id) {
        return supplierService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public SupplierResponse deactivate(@PathVariable Long id) {
        return supplierService.deactivate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        supplierService.delete(id);
    }
}
