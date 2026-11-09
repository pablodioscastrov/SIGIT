package com.inventario.multisucursal.purchases;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreatePurchaseOrderRequest(
        @NotNull Long supplierId,
        @NotNull Long branchId,
        @Size(max = 100) String paymentTerm,
        @NotEmpty @Valid List<CreatePurchaseOrderItemRequest> items) {
}
