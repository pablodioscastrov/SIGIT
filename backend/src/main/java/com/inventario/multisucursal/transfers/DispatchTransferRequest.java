package com.inventario.multisucursal.transfers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * El despacho cubre <b>todas</b> las líneas en un único evento de envío
 * (docs/API_DESIGN.md, sección 7.9: una transferencia tiene un solo tramo,
 * docs/DOMAIN_MODEL.md 2.17) — por eso {@link TransferService} rechaza una
 * solicitud que omita alguna línea.
 */
public record DispatchTransferRequest(
        @Size(max = 150) String carrierName,
        LocalDate estimatedArrivalDate,
        @NotEmpty @Valid List<DispatchTransferItemRequest> items) {
}
