package com.inventario.multisucursal.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * Comparativa entre sucursales (BR-043, RF-035) — solo `MANAGER`/`ADMIN`.
 * No es un promedio ni un ranking calculado aparte: son las mismas cifras
 * que cada sucursal ya expone en su propio dashboard, yuxtapuestas.
 */
public record BranchComparisonResponse(List<BranchMetrics> branches) {

    public record BranchMetrics(
            String branchId, String branchName, BigDecimal currentMonthSales, long activeTransfersCount, long lowStockCount) {
    }
}
