package com.inventario.multisucursal.sales;

import java.math.BigDecimal;

/** Unidades vendidas de un producto en una ventana de tiempo (BR-040, dashboard RF-032). */
public record ProductDemand(Long productId, BigDecimal unitsSold) {
}
