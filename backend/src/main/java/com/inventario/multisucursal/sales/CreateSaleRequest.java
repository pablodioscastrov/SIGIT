package com.inventario.multisucursal.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** {@code priceListId} opcional — si se omite, se resuelve la lista aplicable a la sucursal (ver {@link SaleService}). */
public record CreateSaleRequest(
        @NotNull Long branchId,
        Long priceListId,
        @NotEmpty @Valid List<CreateSaleItemRequest> items) {
}
