package com.inventario.multisucursal.sales;

import java.math.BigDecimal;

/**
 * Totales de ventas confirmadas en un rango de fechas de una sucursal
 * (BR-039, dashboard RF-031). {@code totalSales} nunca es {@code null} — la
 * consulta usa {@code COALESCE} para que un rango sin ventas devuelva 0, no
 * una fila ausente.
 */
public record SalesAggregate(BigDecimal totalSales, long salesCount) {
}
