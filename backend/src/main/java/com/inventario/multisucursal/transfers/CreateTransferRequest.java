package com.inventario.multisucursal.transfers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateTransferRequest(
        @NotNull Long originBranchId,
        @NotNull Long destinationBranchId,
        boolean urgency,
        @NotEmpty @Valid List<CreateTransferItemRequest> items) {
}
