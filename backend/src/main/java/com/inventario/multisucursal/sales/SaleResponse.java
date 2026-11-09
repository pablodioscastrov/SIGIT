package com.inventario.multisucursal.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** {@code soldByUserName} (BR-054): nombre del responsable resuelto por el servicio — {@code GET /users} es ADMIN-only (UC-14), así que el cliente no puede resolverlo por su cuenta. */
public record SaleResponse(
        String id,
        String saleNumber,
        String branchId,
        String soldByUserId,
        String soldByUserName,
        SaleStatus status,
        Instant saleDate,
        List<SaleItemResponse> items,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal total) {

    public static SaleResponse from(Sale sale, List<SaleItem> items, String soldByUserName) {
        return new SaleResponse(
                String.valueOf(sale.getId()),
                sale.getSaleNumber(),
                String.valueOf(sale.getBranchId()),
                String.valueOf(sale.getSoldByUserId()),
                soldByUserName,
                sale.getStatus(),
                sale.getSaleDate(),
                items.stream().map(SaleItemResponse::from).toList(),
                sale.getSubtotal(),
                sale.getDiscountTotal(),
                sale.getTotal());
    }
}
