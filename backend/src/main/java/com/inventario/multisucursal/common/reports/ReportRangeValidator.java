package com.inventario.multisucursal.common.reports;

import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;

import java.time.Instant;

/**
 * Validación compartida por todos los reportes exportables (BR-056): un
 * rango de fechas obligatorio y explícito (nunca se completa con límites
 * amplios por defecto, a diferencia de un listado paginado de la UI — una
 * exportación materializa todo el resultado en memoria de una vez) y un tope
 * de filas, para no cargar cantidades ilimitadas sin control.
 *
 * <p>Vive en {@code common} (no en {@code reports}) porque cada módulo dueño
 * del dato (`inventory`, `sales`, `transfers`) valida su propio reporte
 * exportable antes de que {@code reports} —hoja del grafo de dependencias,
 * docs/ARCHITECTURE.md sección 4— entre en juego; poner esto en
 * {@code reports} obligaría a esos módulos a depender de él, invirtiendo el
 * grafo.
 */
public final class ReportRangeValidator {

    private ReportRangeValidator() {
    }

    public static void requireValidRange(Instant dateFrom, Instant dateTo) {
        if (dateFrom == null || dateTo == null) {
            throw new BusinessRuleViolationException(
                    "RANGO_FECHAS_REQUERIDO", "El reporte exige indicar dateFrom y dateTo — no se asume un rango por defecto.");
        }
        if (dateFrom.isAfter(dateTo)) {
            throw new BusinessRuleViolationException(
                    "RANGO_FECHAS_INVALIDO", "dateFrom (" + dateFrom + ") no puede ser posterior a dateTo (" + dateTo + ").");
        }
    }

    public static void requireWithinRowLimit(long totalElements, int maxRows, String reportLabel) {
        if (totalElements > maxRows) {
            throw new BusinessRuleViolationException(
                    "REPORTE_DEMASIADO_GRANDE",
                    "El reporte de " + reportLabel + " tiene " + totalElements + " filas; reduce el rango de fechas o acota más filtros (máximo " + maxRows + ").");
        }
    }
}
