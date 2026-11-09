package com.inventario.multisucursal.transfers;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DispatchTransferItemRequest(@NotNull Long transferItemId, @NotNull BigDecimal quantityShipped) {
}
