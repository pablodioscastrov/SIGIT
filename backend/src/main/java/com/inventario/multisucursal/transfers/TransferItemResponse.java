package com.inventario.multisucursal.transfers;

import java.math.BigDecimal;

/** docs/openapi.yaml, {@code Transfer.items[]}. */
public record TransferItemResponse(
        String id,
        String productId,
        String unitOfMeasureId,
        BigDecimal quantityRequested,
        BigDecimal quantityApproved,
        BigDecimal quantityShipped,
        BigDecimal quantityReceived,
        BigDecimal quantityMissing,
        DiscrepancyTreatment discrepancyTreatment,
        String followUpTransferId,
        String treatmentNotes) {

    public static TransferItemResponse from(TransferItem item) {
        return new TransferItemResponse(
                String.valueOf(item.getId()),
                String.valueOf(item.getProductId()),
                String.valueOf(item.getUnitOfMeasureId()),
                item.getQuantityRequested(),
                item.getQuantityApproved(),
                item.getQuantityShipped(),
                item.getQuantityReceived(),
                item.getQuantityMissing(),
                item.getDiscrepancyTreatment(),
                item.getFollowUpTransferId() != null ? String.valueOf(item.getFollowUpTransferId()) : null,
                item.getTreatmentNotes());
    }
}
