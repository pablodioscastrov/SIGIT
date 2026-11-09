package com.inventario.multisucursal.purchases;

import java.time.Instant;
import java.util.List;

public record PurchaseOrderResponse(
        String id,
        String orderNumber,
        String supplierId,
        String branchId,
        PurchaseOrderStatus status,
        Instant orderDate,
        String paymentTerm,
        List<PurchaseOrderItemResponse> items) {

    public static PurchaseOrderResponse from(PurchaseOrder order, List<PurchaseOrderItem> items) {
        return new PurchaseOrderResponse(
                String.valueOf(order.getId()),
                order.getOrderNumber(),
                String.valueOf(order.getSupplierId()),
                String.valueOf(order.getBranchId()),
                order.getStatus(),
                order.getOrderDate(),
                order.getPaymentTerm(),
                items.stream().map(PurchaseOrderItemResponse::from).toList());
    }
}
