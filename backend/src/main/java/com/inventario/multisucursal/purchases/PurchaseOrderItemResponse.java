package com.inventario.multisucursal.purchases;

import java.math.BigDecimal;

public record PurchaseOrderItemResponse(
        String id,
        String productId,
        String unitOfMeasureId,
        BigDecimal quantityOrdered,
        BigDecimal quantityReceived,
        BigDecimal pending,
        BigDecimal unitPrice,
        BigDecimal discountPercentage,
        BigDecimal lineTotal) {

    public static PurchaseOrderItemResponse from(PurchaseOrderItem item) {
        return new PurchaseOrderItemResponse(
                String.valueOf(item.getId()),
                String.valueOf(item.getProductId()),
                String.valueOf(item.getUnitOfMeasureId()),
                item.getQuantityOrdered(),
                item.getQuantityReceived(),
                item.pending(),
                item.getUnitPrice(),
                item.getDiscountPercentage(),
                item.getLineTotal());
    }
}
