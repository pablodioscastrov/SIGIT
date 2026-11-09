package com.inventario.multisucursal.sales;

import java.math.BigDecimal;

/**
 * docs/openapi.yaml, Sale.items[]. {@code id} se expone desde BR-052: una
 * devolución (`POST /sales/{id}/returns`) necesita referenciar una línea de
 * venta concreta, algo que ninguna acción de cliente necesitaba antes de
 * que existieran las devoluciones. {@code quantityReturned}/{@code pending}
 * exponen cuánto de la línea ya se devolvió y cuánto queda disponible.
 */
public record SaleItemResponse(
        String id, String productId, BigDecimal quantity, String unitOfMeasureId, BigDecimal unitPrice,
        BigDecimal discountPercentage, BigDecimal lineTotal, BigDecimal quantityReturned, BigDecimal pending) {

    public static SaleItemResponse from(SaleItem item) {
        return new SaleItemResponse(
                String.valueOf(item.getId()),
                String.valueOf(item.getProductId()),
                item.getQuantity(),
                String.valueOf(item.getUnitOfMeasureId()),
                item.getUnitPrice(),
                item.getDiscountPercentage(),
                item.getLineTotal(),
                item.getQuantityReturned(),
                item.pending());
    }
}
