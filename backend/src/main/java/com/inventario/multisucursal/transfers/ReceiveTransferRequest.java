package com.inventario.multisucursal.transfers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * A diferencia del despacho, la recepción admite un subconjunto de líneas
 * por llamada: el conteo físico puede hacerse línea por línea en momentos
 * distintos (docs/API_DESIGN.md, sección 7.9; escenario 3.5).
 */
public record ReceiveTransferRequest(@NotEmpty @Valid List<ReceiveTransferItemRequest> items) {
}
