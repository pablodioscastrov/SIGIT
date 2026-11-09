package com.inventario.multisucursal.transfers;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ApproveTransferItemRequest(@NotNull Long transferItemId, @NotNull BigDecimal quantityApproved) {
}
