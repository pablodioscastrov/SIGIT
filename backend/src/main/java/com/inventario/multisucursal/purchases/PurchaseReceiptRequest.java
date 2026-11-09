package com.inventario.multisucursal.purchases;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record PurchaseReceiptRequest(@NotEmpty @Valid List<ReceiptItemRequest> items) {
}
