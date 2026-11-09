package com.inventario.multisucursal.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** BR-052: devolución total o parcial de una venta confirmada, una o varias líneas a la vez. */
public record SaleReturnRequest(@NotEmpty @Valid List<SaleReturnItemRequest> items) {
}
