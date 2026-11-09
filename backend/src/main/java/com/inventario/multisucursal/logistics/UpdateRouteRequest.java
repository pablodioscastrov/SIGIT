package com.inventario.multisucursal.logistics;

import jakarta.validation.constraints.NotNull;

/** Solo la clasificación es editable: el par origen-destino es la identidad de la ruta. */
public record UpdateRouteRequest(@NotNull RouteClassification classification) {
}
