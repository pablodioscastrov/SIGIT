package com.inventario.multisucursal.logistics;

import com.inventario.multisucursal.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/API_DESIGN.md, sección 7.9 (routes) y sección 6: lectura abierta, escritura MANAGER + ADMIN. */
@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping
    public PageResponse<RouteResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) RouteClassification classification,
            @PageableDefault(size = 20) Pageable pageable) {
        return routeService.list(branchId, classification, pageable);
    }

    @GetMapping("/{id}")
    public RouteResponse get(@PathVariable Long id) {
        return routeService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public RouteResponse create(@Valid @RequestBody CreateRouteRequest request) {
        return routeService.create(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public RouteResponse reclassify(@PathVariable Long id, @Valid @RequestBody UpdateRouteRequest request) {
        return routeService.reclassify(id, request);
    }
}
