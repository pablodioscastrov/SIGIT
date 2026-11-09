package com.inventario.multisucursal.suppliers;

public record SupplierResponse(
        String id,
        String name,
        String taxId,
        String contactName,
        String phone,
        String email,
        boolean active) {

    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(
                String.valueOf(supplier.getId()),
                supplier.getName(),
                supplier.getTaxId(),
                supplier.getContactName(),
                supplier.getPhone(),
                supplier.getEmail(),
                supplier.isActive());
    }
}
