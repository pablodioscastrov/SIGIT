package com.inventario.multisucursal.reports;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.common.exception.BranchAccessDeniedException;
import com.inventario.multisucursal.logistics.Route;
import com.inventario.multisucursal.logistics.RouteService;
import com.inventario.multisucursal.transfers.Transfer;
import com.inventario.multisucursal.transfers.TransferService;
import com.inventario.multisucursal.transfers.TransferStatus;
import com.inventario.multisucursal.users.RoleCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cumplimiento logístico: estimado vs. real, por sucursal y por ruta
 * (RF-027, RF-030; UC-12).
 *
 * <p>{@code reports} es hoja del grafo de dependencias
 * (docs/ARCHITECTURE.md, sección 4): solo lee, nunca escribe, y lo hace a
 * través de las capas de servicio de {@code transfers} y {@code logistics},
 * no de sus repositorios. Es también el único punto del sistema que cruza
 * ambos módulos — {@code logistics} no conoce {@code Transfer} y
 * {@code transfers} no calcula métricas.
 *
 * <p><b>Toda cifra se deriva de datos ya persistidos</b> por el flujo de
 * transferencias: {@code dispatchedAt}, {@code receivedAt},
 * {@code estimatedArrivalDate} y {@code status}. No existe ninguna columna de
 * "cumplimiento" mantenida a mano que pudiera contradecir esos hechos.
 */
@Service
public class LogisticsComplianceService {

    private static final Instant MIN_DISPATCHED_AT = Instant.parse("1900-01-01T00:00:00Z");
    private static final Instant MAX_DISPATCHED_AT = Instant.parse("9999-12-31T23:59:59Z");

    private final TransferService transferService;
    private final RouteService routeService;

    public LogisticsComplianceService(TransferService transferService, RouteService routeService) {
        this.transferService = transferService;
        this.routeService = routeService;
    }

    public LogisticsComplianceResponse report(Long branchId, Long routeId, Instant dispatchedFrom, Instant dispatchedTo) {
        Long effectiveBranchId = resolveBranchScope(branchId);
        Instant from = dispatchedFrom != null ? dispatchedFrom : MIN_DISPATCHED_AT;
        Instant to = dispatchedTo != null ? dispatchedTo : MAX_DISPATCHED_AT;

        // El filtro por ruta se traduce a su par de sucursales: ese par es la
        // identidad de la ruta y no puede quedar desincronizado, a diferencia
        // del route_id materializado en cada transferencia (BR-036).
        Route route = routeId != null ? routeService.requireById(routeId) : null;
        Long originFilter = route != null ? route.getOriginBranchId() : null;
        Long destinationFilter = route != null ? route.getDestinationBranchId() : null;

        List<Transfer> transfers = transferService.findDispatchedForCompliance(
                effectiveBranchId, originFilter, destinationFilter, from, to);

        Map<BranchPair, List<Transfer>> groupedByRoute = new LinkedHashMap<>();
        for (Transfer transfer : transfers) {
            groupedByRoute.computeIfAbsent(
                    new BranchPair(transfer.getOriginBranchId(), transfer.getDestinationBranchId()),
                    key -> new ArrayList<>()).add(transfer);
        }

        List<LogisticsComplianceResponse.RouteCompliance> byRoute = new ArrayList<>();
        for (Map.Entry<BranchPair, List<Transfer>> entry : groupedByRoute.entrySet()) {
            BranchPair pair = entry.getKey();
            Optional<Route> pairRoute = routeService.findByBranchPair(pair.originBranchId(), pair.destinationBranchId());
            byRoute.add(new LogisticsComplianceResponse.RouteCompliance(
                    pairRoute.map(r -> String.valueOf(r.getId())).orElse(null),
                    String.valueOf(pair.originBranchId()),
                    String.valueOf(pair.destinationBranchId()),
                    pairRoute.map(Route::getClassification).orElse(null),
                    computeMetrics(entry.getValue())));
        }

        return new LogisticsComplianceResponse(
                new LogisticsComplianceResponse.AppliedFilters(
                        effectiveBranchId != null ? String.valueOf(effectiveBranchId) : null,
                        routeId != null ? String.valueOf(routeId) : null,
                        dispatchedFrom, dispatchedTo),
                computeMetrics(transfers),
                byRoute);
    }

    private LogisticsComplianceResponse.ComplianceMetrics computeMetrics(List<Transfer> transfers) {
        long delivered = 0, inTransit = 0, overdueInTransit = 0, onTime = 0, late = 0, notEvaluable = 0, withShortages = 0;
        long totalDeliveryMinutes = 0;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        for (Transfer transfer : transfers) {
            if (transfer.getReceivedAt() == null) {
                inTransit++;
                // Riesgo visible de una entrega en curso: la fecha estimada ya pasó
                // y todavía no llega (RF-029). Derivado, no almacenado.
                if (transfer.getEstimatedArrivalDate() != null && today.isAfter(transfer.getEstimatedArrivalDate())) {
                    overdueInTransit++;
                }
                continue;
            }

            delivered++;
            totalDeliveryMinutes += Duration.between(transfer.getDispatchedAt(), transfer.getReceivedAt()).toMinutes();

            if (transfer.getStatus() == TransferStatus.RECEIVED_PARTIAL || transfer.getStatus() == TransferStatus.CLOSED) {
                withShortages++;
            }

            if (transfer.getEstimatedArrivalDate() == null) {
                // Despachada sin comprometer fecha: no hay contra qué comparar. Se
                // cuenta aparte en vez de asumirla puntual, que inflaría el indicador.
                notEvaluable++;
            } else if (!transfer.getReceivedAt().atZone(ZoneOffset.UTC).toLocalDate().isAfter(transfer.getEstimatedArrivalDate())) {
                onTime++;
            } else {
                late++;
            }
        }

        long evaluable = onTime + late;
        BigDecimal complianceRate = evaluable == 0
                ? null
                : BigDecimal.valueOf(onTime).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(evaluable), 2, RoundingMode.HALF_UP);
        BigDecimal averageDeliveryHours = delivered == 0
                ? null
                : BigDecimal.valueOf(totalDeliveryMinutes).divide(BigDecimal.valueOf(60L * delivered), 2, RoundingMode.HALF_UP);

        return new LogisticsComplianceResponse.ComplianceMetrics(
                transfers.size(), delivered, inTransit, overdueInTransit, onTime, late, notEvaluable, withShortages,
                complianceRate, averageDeliveryHours);
    }

    /**
     * docs/API_DESIGN.md, sección 6: este reporte es de "propia sucursal para
     * {@code OPERATOR}; cualquiera para {@code MANAGER}/{@code ADMIN}" —
     * mismo criterio que {@code DashboardService.requireBranch}, no una
     * excepción aislada.
     */
    private Long resolveBranchScope(Long requestedBranchId) {
        AuthenticatedUser user = (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (user.role() == RoleCode.OPERATOR) {
            if (requestedBranchId != null && !requestedBranchId.equals(user.branchId())) {
                throw new BranchAccessDeniedException("Un operador solo puede consultar el cumplimiento logístico de su propia sucursal.");
            }
            return user.branchId();
        }
        return requestedBranchId;
    }

    private record BranchPair(Long originBranchId, Long destinationBranchId) {
    }
}
