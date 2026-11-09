package com.inventario.multisucursal.transfers;

/** docs/API_DESIGN.md, ejemplo 9.7 — respuesta puntual del tratamiento, no la transferencia completa. */
public record DiscrepancyTreatmentResponse(
        String transferItemId,
        DiscrepancyTreatment discrepancyTreatment,
        String notes,
        String followUpTransferId,
        TransferStatus transferStatus) {
}
