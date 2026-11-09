package com.inventario.multisucursal.transfers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ApproveTransferRequest(@NotEmpty @Valid List<ApproveTransferItemRequest> items) {
}
