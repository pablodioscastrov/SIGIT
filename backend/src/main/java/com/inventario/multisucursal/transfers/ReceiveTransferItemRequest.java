package com.inventario.multisucursal.transfers;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * {@code quantityReceived} admite 0: "no llegó nada de esta línea" es una
 * recepción parcial válida, no un error (docs/CRITICAL_FLOWS.md, flujo F1).
 */
public record ReceiveTransferItemRequest(@NotNull Long transferItemId, @NotNull BigDecimal quantityReceived) {
}
