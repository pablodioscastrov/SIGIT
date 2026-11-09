package com.inventario.multisucursal.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ventas del mes en curso vs. meses anteriores (BR-039, RF-031).
 *
 * @param previousMonths ordenado cronológicamente ascendente (el más antiguo
 *                        primero, el más reciente al final) — leído junto a
 *                        {@code currentMonth} forma una línea de tiempo
 *                        natural de izquierda a derecha.
 * @param growthVsPreviousMonthPercentage variación porcentual del mes actual
 *        contra el inmediatamente anterior; {@code null} cuando el mes
 *        anterior no tuvo ventas (no se muestra {@code Infinity} ni un 0%
 *        engañoso).
 */
public record SalesTrendResponse(
        String branchId,
        String branchName,
        MonthlySales currentMonth,
        List<MonthlySales> previousMonths,
        BigDecimal growthVsPreviousMonthPercentage) {

    public record MonthlySales(String period, BigDecimal totalSales, long salesCount) {
    }
}
