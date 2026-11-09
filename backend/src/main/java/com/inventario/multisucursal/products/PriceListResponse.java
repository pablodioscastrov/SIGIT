package com.inventario.multisucursal.products;

public record PriceListResponse(String id, String name, String branchId, boolean active) {

    public static PriceListResponse from(PriceList priceList) {
        return new PriceListResponse(
                String.valueOf(priceList.getId()),
                priceList.getName(),
                priceList.getBranchId() != null ? String.valueOf(priceList.getBranchId()) : null,
                priceList.isActive());
    }
}
