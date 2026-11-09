package com.inventario.multisucursal.logistics;

public record RouteResponse(String id, String originBranchId, String destinationBranchId, RouteClassification classification) {

    public static RouteResponse from(Route route) {
        return new RouteResponse(
                String.valueOf(route.getId()),
                String.valueOf(route.getOriginBranchId()),
                String.valueOf(route.getDestinationBranchId()),
                route.getClassification());
    }
}
