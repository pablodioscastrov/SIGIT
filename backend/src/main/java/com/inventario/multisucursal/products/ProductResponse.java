package com.inventario.multisucursal.products;

import java.math.BigDecimal;

/**
 * {@code salePrice} (BR-051): el precio vigente del producto en la lista de
 * precios global por defecto, resuelto en el momento de la consulta — nunca
 * un valor propio de {@code Product} (que no tiene columna de precio). Es
 * {@code null} para productos creados antes de esta fase o sin precio
 * vigente en esa lista.
 */
public record ProductResponse(
        String id,
        String sku,
        String name,
        String description,
        String baseUnitOfMeasureId,
        boolean active,
        BigDecimal minimumStock,
        BigDecimal salePrice) {

    public static ProductResponse from(Product product, BigDecimal salePrice) {
        return new ProductResponse(
                String.valueOf(product.getId()),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                String.valueOf(product.getBaseUnitOfMeasureId()),
                product.isActive(),
                product.getMinimumStock(),
                salePrice);
    }
}
