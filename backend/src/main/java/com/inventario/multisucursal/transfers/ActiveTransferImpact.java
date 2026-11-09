package com.inventario.multisucursal.transfers;

import java.math.BigDecimal;

/**
 * Transferencia activa con su impacto en inventario, distinguiendo lo ya
 * ocurrido de lo proyectado (BR-041, dashboard RF-033).
 *
 * @param unitsInTransit      ya salió del origen y aún no llega al destino
 *                            (efecto real, ya aplicado)
 * @param unitsPendingDispatch pendiente de descontarse del origen cuando se
 *                             despache (efecto proyectado, no aplicado)
 */
public record ActiveTransferImpact(
        Long transferId,
        String transferNumber,
        TransferStatus status,
        Long originBranchId,
        Long destinationBranchId,
        boolean urgency,
        BigDecimal unitsInTransit,
        BigDecimal unitsPendingDispatch) {
}
