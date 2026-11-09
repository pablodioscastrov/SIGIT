package com.inventario.multisucursal.logistics;

import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.common.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Catálogo de rutas clasificadas (RF-028). Lectura abierta a cualquier rol
 * autenticado; escritura {@code MANAGER}/{@code ADMIN}
 * (docs/API_DESIGN.md, sección 6).
 *
 * <p>Este servicio <b>no conoce {@code Transfer}</b>: el grafo de
 * dependencias aprobado (docs/ARCHITECTURE.md, sección 4) va
 * {@code transfers → logistics}, nunca al revés. Quien necesita cruzar
 * rutas con transferencias es {@code reports}, que es hoja del grafo y puede
 * leer de ambos.
 */
@Service
public class RouteService {

    private final RouteRepository routeRepository;
    private final BranchRepository branchRepository;

    public RouteService(RouteRepository routeRepository, BranchRepository branchRepository) {
        this.routeRepository = routeRepository;
        this.branchRepository = branchRepository;
    }

    @Transactional
    public RouteResponse create(CreateRouteRequest request) {
        if (request.originBranchId().equals(request.destinationBranchId())) {
            throw new BusinessRuleViolationException("ORIGEN_IGUAL_DESTINO", "El origen y el destino deben ser sucursales distintas.");
        }
        requireBranch(request.originBranchId(), "origen");
        requireBranch(request.destinationBranchId(), "destino");
        if (routeRepository.findByOriginBranchIdAndDestinationBranchId(request.originBranchId(), request.destinationBranchId()).isPresent()) {
            throw new ResourceConflictException("RUTA_YA_EXISTE", "Ya existe una ruta clasificada para ese par origen-destino.");
        }
        return RouteResponse.from(routeRepository.save(
                new Route(request.originBranchId(), request.destinationBranchId(), request.classification())));
    }

    public PageResponse<RouteResponse> list(Long branchId, RouteClassification classification, Pageable pageable) {
        return PageResponse.from(routeRepository.search(branchId, classification, pageable).map(RouteResponse::from));
    }

    public RouteResponse getById(Long id) {
        return RouteResponse.from(findOrThrow(id));
    }

    @Transactional
    public RouteResponse reclassify(Long id, UpdateRouteRequest request) {
        Route route = findOrThrow(id);
        route.reclassify(request.classification());
        return RouteResponse.from(route);
    }

    /**
     * Resolución de la ruta a partir del par de sucursales — el punto de
     * entrada que usa {@code transfers} para etiquetar una transferencia sin
     * que nadie tenga que teclear el id de la ruta.
     */
    public Optional<Route> findByBranchPair(Long originBranchId, Long destinationBranchId) {
        return routeRepository.findByOriginBranchIdAndDestinationBranchId(originBranchId, destinationBranchId);
    }

    public Route requireById(Long id) {
        return findOrThrow(id);
    }

    private Route findOrThrow(Long id) {
        return routeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RUTA_NO_ENCONTRADA", "Ruta no encontrada."));
    }

    private void requireBranch(Long branchId, String label) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("SUCURSAL_NO_ENCONTRADA", "Sucursal " + label + " no encontrada."));
        if (!branch.isActive()) {
            throw new ResourceConflictException("SUCURSAL_INACTIVA", "La sucursal " + label + " está inactiva.");
        }
    }
}
