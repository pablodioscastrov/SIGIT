package com.inventario.multisucursal.logistics;

import jakarta.validation.constraints.NotNull;

public record CreateRouteRequest(
        @NotNull Long originBranchId,
        @NotNull Long destinationBranchId,
        @NotNull RouteClassification classification) {
}
