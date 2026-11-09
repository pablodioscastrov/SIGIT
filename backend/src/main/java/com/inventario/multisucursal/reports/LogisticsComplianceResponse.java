package com.inventario.multisucursal.reports;

import com.inventario.multisucursal.logistics.RouteClassification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Reporte de cumplimiento logístico (RF-027, RF-030). Todas las cifras se
 * derivan de datos ya persistidos en {@code Transfer} — {@code dispatchedAt},
 * {@code receivedAt}, {@code estimatedArrivalDate} y {@code status} — sin
 * ningún campo de "cumplimiento" almacenado a mano que pudiera contradecir
 * los hechos (BR-038).
 *
 * @param appliedFilters eco de los filtros efectivamente aplicados: para un
 *        {@code OPERATOR} la sucursal se fuerza a la suya, y conviene que la
 *        respuesta lo diga en vez de aparentar un alcance global.
 */
public record LogisticsComplianceResponse(
        AppliedFilters appliedFilters,
        ComplianceMetrics summary,
        List<RouteCompliance> byRoute) {

    public record AppliedFilters(String branchId, String routeId, Instant dispatchedFrom, Instant dispatchedTo) {
    }

    /**
     * @param dispatched          transferencias despachadas en el rango (base del reporte)
     * @param delivered           ya recibidas (completa o parcialmente)
     * @param inTransit           despachadas y aún sin recibir
     * @param overdueInTransit    en tránsito cuya fecha estimada ya pasó (RF-029: visibilidad del riesgo en curso)
     * @param onTime              recibidas en o antes de la fecha estimada
     * @param late                recibidas después de la fecha estimada
     * @param notEvaluable        recibidas pero despachadas sin fecha estimada: no se pueden juzgar y no se cuentan como cumplidas
     * @param withShortages       recibidas con faltante en al menos una línea (recepción parcial)
     * @param complianceRate      {@code onTime / (onTime + late)} en porcentaje; nulo si no hay ninguna entrega evaluable
     * @param averageDeliveryHours promedio real de {@code receivedAt - dispatchedAt}; nulo si no hay entregas
     */
    public record ComplianceMetrics(
            long dispatched,
            long delivered,
            long inTransit,
            long overdueInTransit,
            long onTime,
            long late,
            long notEvaluable,
            long withShortages,
            BigDecimal complianceRate,
            BigDecimal averageDeliveryHours) {
    }

    /**
     * @param routeId nulo si el par origen-destino todavía no tiene una ruta
     *        clasificada: la transferencia igual se reporta, agrupada por su
     *        par de sucursales (BR-036)
     */
    public record RouteCompliance(
            String routeId,
            String originBranchId,
            String destinationBranchId,
            RouteClassification classification,
            ComplianceMetrics metrics) {
    }
}
