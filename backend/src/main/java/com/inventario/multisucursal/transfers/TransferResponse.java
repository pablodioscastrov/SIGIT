package com.inventario.multisucursal.transfers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TransferResponse(
        String id,
        String transferNumber,
        TransferStatus status,
        String originBranchId,
        String destinationBranchId,
        String routeId,
        boolean urgency,
        String carrierName,
        LocalDate estimatedArrivalDate,
        String requestedByUserId,
        String approvedByUserId,
        Instant requestedAt,
        Instant approvedAt,
        Instant dispatchedAt,
        Instant receivedAt,
        List<TransferItemResponse> items) {

    public static TransferResponse from(Transfer transfer, List<TransferItem> items) {
        return new TransferResponse(
                String.valueOf(transfer.getId()),
                transfer.getTransferNumber(),
                transfer.getStatus(),
                String.valueOf(transfer.getOriginBranchId()),
                String.valueOf(transfer.getDestinationBranchId()),
                transfer.getRouteId() != null ? String.valueOf(transfer.getRouteId()) : null,
                transfer.isUrgency(),
                transfer.getCarrierName(),
                transfer.getEstimatedArrivalDate(),
                String.valueOf(transfer.getRequestedByUserId()),
                transfer.getApprovedByUserId() != null ? String.valueOf(transfer.getApprovedByUserId()) : null,
                transfer.getRequestedAt(),
                transfer.getApprovedAt(),
                transfer.getDispatchedAt(),
                transfer.getReceivedAt(),
                items.stream().map(TransferItemResponse::from).toList());
    }
}
