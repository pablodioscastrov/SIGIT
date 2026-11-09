package com.inventario.multisucursal.branches;

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
 * docs/API_DESIGN.md, sección 7.3. Lectura abierta a cualquier rol
 * autenticado (RF-003: el inventario/catálogo de cualquier sucursal es de
 * lectura pública dentro de la organización) — ya cubierto por
 * {@code anyRequest().authenticated()} en {@code SecurityConfig}, sin
 * necesitar {@code @PreAuthorize} adicional aquí. Escritura restringida a
 * ADMIN (UC-15).
 */
@RestController
@RequestMapping("/api/v1/branches")
public class BranchController {

    private final BranchService branchService;

    public BranchController(BranchService branchService) {
        this.branchService = branchService;
    }

    @GetMapping
    public PageResponse<BranchResponse> list(
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable) {
        return branchService.list(active, pageable);
    }

    @GetMapping("/{id}")
    public BranchResponse get(@PathVariable Long id) {
        return branchService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public BranchResponse create(@Valid @RequestBody CreateBranchRequest request) {
        return branchService.create(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public BranchResponse update(@PathVariable Long id, @Valid @RequestBody UpdateBranchRequest request) {
        return branchService.update(id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public BranchResponse activate(@PathVariable Long id) {
        return branchService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public BranchResponse deactivate(@PathVariable Long id) {
        return branchService.deactivate(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        branchService.delete(id);
    }
}
