package com.inventario.multisucursal.dashboard;

import com.inventario.multisucursal.transfers.TransferStatus;

import java.math.BigDecimal;
import java.util.List;

/** Transferencias activas y su impacto en inventario (BR-041, RF-033). */
public record ActiveTransfersResponse(
        String branchId,
        String branchName,
        long activeCount,
        BigDecimal totalUnitsInTransit,
        BigDecimal totalUnitsPendingDispatch,
        List<ActiveTransferEntry> transfers) {

    public record ActiveTransferEntry(
            String transferId,
            String transferNumber,
            TransferStatus status,
            String originBranchId,
            String destinationBranchId,
            boolean urgency,
            BigDecimal unitsInTransit,
            BigDecimal unitsPendingDispatch) {
    }
}
