package com.inventario.multisucursal.datalake;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.inventario.multisucursal.events.DomainEvent;
import com.inventario.multisucursal.inventory.InventoryMovement;
import com.inventario.multisucursal.inventory.InventoryMovementRepository;
import com.inventario.multisucursal.purchases.PurchaseOrder;
import com.inventario.multisucursal.purchases.PurchaseOrderItem;
import com.inventario.multisucursal.purchases.PurchaseOrderItemRepository;
import com.inventario.multisucursal.purchases.PurchaseOrderRepository;
import com.inventario.multisucursal.sales.Sale;
import com.inventario.multisucursal.sales.SaleItem;
import com.inventario.multisucursal.sales.SaleItemRepository;
import com.inventario.multisucursal.sales.SaleRepository;
import com.inventario.multisucursal.transfers.Transfer;
import com.inventario.multisucursal.transfers.TransferItem;
import com.inventario.multisucursal.transfers.TransferItemRepository;
import com.inventario.multisucursal.transfers.TransferRepository;

/**
 * Sumidero del Data Lake SIGIT.
 *
 * <p>Escucha los {@link DomainEvent} que ya publica el sistema post-commit
 * (la misma infraestructura que alimenta SSE), los enriquece con los datos
 * completos del documento de negocio correspondiente y los persiste como JSON
 * en la zona {@code landing/} de Azure Blob Storage / Azurite.
 *
 * <h2>Garantías</h2>
 * <ul>
 *   <li>Se ejecuta <b>después del commit</b> ({@code AFTER_COMMIT}): la
 *       transacción de negocio ya terminó con éxito antes de que este método
 *       se invoque. Un rollback nunca genera un evento en el Data Lake.</li>
 *   <li>Se ejecuta de forma <b>asíncrona</b> ({@code @Async}): no bloquea el
 *       hilo HTTP que procesó la operación de negocio.</li>
 *   <li>Nunca propaga excepciones: cualquier fallo se registra en el log pero
 *       no afecta la respuesta al cliente ni a operaciones futuras.</li>
 * </ul>
 *
 * <h2>Estructura del Data Lake</h2>
 * <pre>
 * landing/
 *   inventory-movement/{año}/{mes}/{día}/{ts}-{id}.json   ← todo movimiento de stock
 *   sale/{año}/{mes}/{día}/{ts}-{id}.json                 ← ventas confirmadas (doc. completo)
 *   purchase-order/{año}/{mes}/{día}/{ts}-{id}.json       ← compras al recibir (doc. completo)
 *   transfer/{año}/{mes}/{día}/{ts}-{id}.json             ← transferencias (al cambiar estado)
 *   stock-alert/{año}/{mes}/{día}/{ts}-{id}.json          ← alertas de stock mínimo
 * </pre>
 *
 * <p>Solo se instancia cuando el {@link DataLakeWriter} está disponible
 * (es decir, cuando {@code app.datalake.enabled=true}).
 */
@Component
@ConditionalOnBean(DataLakeWriter.class)
public class DataLakeSink {

    private static final Logger log = LoggerFactory.getLogger(DataLakeSink.class);

    private final DataLakeWriter writer;
    private final InventoryMovementRepository movementRepo;
    private final SaleRepository saleRepo;
    private final SaleItemRepository saleItemRepo;
    private final PurchaseOrderRepository purchaseOrderRepo;
    private final PurchaseOrderItemRepository purchaseOrderItemRepo;
    private final TransferRepository transferRepo;
    private final TransferItemRepository transferItemRepo;

    public DataLakeSink(
            DataLakeWriter writer,
            InventoryMovementRepository movementRepo,
            SaleRepository saleRepo,
            SaleItemRepository saleItemRepo,
            PurchaseOrderRepository purchaseOrderRepo,
            PurchaseOrderItemRepository purchaseOrderItemRepo,
            TransferRepository transferRepo,
            TransferItemRepository transferItemRepo) {
        this.writer = writer;
        this.movementRepo = movementRepo;
        this.saleRepo = saleRepo;
        this.saleItemRepo = saleItemRepo;
        this.purchaseOrderRepo = purchaseOrderRepo;
        this.purchaseOrderItemRepo = purchaseOrderItemRepo;
        this.transferRepo = transferRepo;
        this.transferItemRepo = transferItemRepo;
    }

    /**
     * Listener principal. Cada tipo de evento se despacha al método
     * correspondiente que sabe qué datos enriquecer y qué escribir en el
     * Data Lake.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEvent event) {
        try {
            switch (event.type()) {
                case DomainEvent.INVENTORY_UPDATED -> handleInventoryUpdated(event);
                case DomainEvent.STOCK_ALERT_TRIGGERED,
                     DomainEvent.STOCK_ALERT_RESOLVED -> handleStockAlert(event);
                case DomainEvent.TRANSFER_STATUS_CHANGED,
                     DomainEvent.TRANSFER_DISCREPANCY_OPENED -> handleTransferEvent(event);
                case DomainEvent.SALE_CONFIRMED -> handleSaleConfirmed(event);
                case DomainEvent.PURCHASE_ORDER_RECEIVED -> handlePurchaseOrderReceived(event);
                default -> log.debug("Data Lake — evento ignorado: {}", event.type());
            }
        } catch (Exception e) {
            log.error("Data Lake — error no controlado procesando evento {}: {}", event.type(), e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Manejadores por tipo de evento
    // -------------------------------------------------------------------------

    /**
     * Un movimiento de inventario ocurrió. Buscamos el movimiento más reciente
     * del producto en esa sucursal para registrarlo en el Data Lake.
     * El movimiento incluye motivo (COMPRA, VENTA, TRANSFERENCIA_*, AJUSTE_*, etc.)
     * lo que permite análisis segmentados por tipo de operación.
     */
    private void handleInventoryUpdated(DomainEvent event) {
        Long productId = Long.parseLong(event.resourceId());
        event.branchIds().forEach(branchId -> {
            movementRepo.search(branchId, productId, null,
                    Instant.EPOCH, Instant.now(),
                    org.springframework.data.domain.PageRequest.of(0, 1,
                            org.springframework.data.domain.Sort.by("occurredAt").descending()))
                    .stream()
                    .findFirst()
                    .ifPresent(movement -> {
                        InventoryMovementRecord record = InventoryMovementRecord.from(movement);
                        writer.write("inventory-movement", String.valueOf(movement.getId()), record);
                    });
        });
    }

    /**
     * Alerta de stock mínimo disparada o resuelta.
     * Se registra en el Data Lake como señal de análisis de patrones de
     * reabastecimiento y comportamiento de stock por sucursal/producto.
     */
    private void handleStockAlert(DomainEvent event) {
        StockAlertRecord record = new StockAlertRecord(
                event.type(),
                Long.parseLong(event.resourceId()),
                event.branchIds().stream().findFirst().orElse(null),
                event.occurredAt());
        writer.write("stock-alert", event.resourceId() + "-" + event.occurredAt().toEpochMilli(), record);
    }

    /**
     * Una transferencia cambió de estado. Se persiste el estado completo de la
     * transferencia con sus ítems para análisis de ciclos logísticos,
     * cumplimiento de rutas y tiempos de tránsito.
     */
    private void handleTransferEvent(DomainEvent event) {
        Long transferId = Long.parseLong(event.resourceId());
        transferRepo.findById(transferId).ifPresent(transfer -> {
            List<TransferItem> items = transferItemRepo.findByTransferId(transferId);
            TransferRecord record = TransferRecord.from(transfer, items, event.type(), event.occurredAt());
            writer.write("transfer", String.valueOf(transferId), record);
        });
    }

    /**
     * Venta confirmada. Persiste el documento completo de venta con todas sus
     * líneas (número, totales, descuentos, productos, precios efectivos) para
     * análisis de mercado: tendencias de ventas, productos más vendidos,
     * ingresos por sucursal, efectividad de descuentos, rotación de inventario.
     */
    private void handleSaleConfirmed(DomainEvent event) {
        Long saleId = Long.parseLong(event.resourceId());
        saleRepo.findById(saleId).ifPresent(sale -> {
            List<SaleItem> items = saleItemRepo.findBySaleId(saleId);
            SaleRecord record = SaleRecord.from(sale, items, event.occurredAt());
            writer.write("sale", String.valueOf(saleId), record);
        });
    }

    /**
     * Recepción de compra registrada (total o parcial). Persiste el documento
     * completo de la orden con sus líneas (proveedor, condiciones comerciales,
     * cantidades ordenadas vs. recibidas, costos pactados) para análisis
     * operativo: comportamiento de proveedores, cumplimiento de entregas,
     * análisis de costos de aprovisionamiento por producto y sucursal.
     */
    private void handlePurchaseOrderReceived(DomainEvent event) {
        Long orderId = Long.parseLong(event.resourceId());
        purchaseOrderRepo.findById(orderId).ifPresent(order -> {
            List<PurchaseOrderItem> items = purchaseOrderItemRepo.findByPurchaseOrderId(orderId);
            PurchaseOrderRecord record = PurchaseOrderRecord.from(order, items, event.occurredAt());
            writer.write("purchase-order", String.valueOf(orderId), record);
        });
    }

    // -------------------------------------------------------------------------
    // Records de payload (estructura del JSON en el Data Lake)
    // -------------------------------------------------------------------------

    /**
     * Payload de un movimiento de inventario en el Data Lake.
     * Refleja exactamente el ledger append-only de {@link InventoryMovement}.
     */
    public record InventoryMovementRecord(
            Long id,
            Long productId,
            Long branchId,
            String direction,
            String reason,
            BigDecimal quantity,
            Long unitOfMeasureId,
            Long responsibleUserId,
            Instant occurredAt,
            String notes,
            Long purchaseOrderItemId,
            Long saleItemId,
            Long transferItemId,
            Instant capturedAt) {

        public static InventoryMovementRecord from(InventoryMovement m) {
            return new InventoryMovementRecord(
                    m.getId(), m.getProductId(), m.getBranchId(),
                    m.getDirection().name(), m.getReason().name(),
                    m.getQuantity(), m.getUnitOfMeasureId(), m.getResponsibleUserId(),
                    m.getOccurredAt(), m.getNotes(),
                    m.getPurchaseOrderItemId(), m.getSaleItemId(), m.getTransferItemId(),
                    Instant.now());
        }
    }

    /** Payload de una alerta de stock en el Data Lake. */
    public record StockAlertRecord(
            String alertType,
            Long productId,
            Long branchId,
            Instant occurredAt) {}

    /**
     * Payload de una transferencia en el Data Lake con sus ítems.
     * Permite analizar KPIs logísticos: tiempos de ciclo, tasas de faltante,
     * cumplimiento por ruta, etc.
     */
    public record TransferRecord(
            Long id,
            String transferNumber,
            Long originBranchId,
            Long destinationBranchId,
            String status,
            boolean urgency,
            Long routeId,
            String carrierName,
            LocalDate estimatedArrivalDate,
            Instant requestedAt,
            Instant approvedAt,
            Instant dispatchedAt,
            Instant receivedAt,
            List<TransferItemRecord> items,
            String triggeringEvent,
            Instant capturedAt) {

        public static TransferRecord from(Transfer t, List<TransferItem> items,
                String triggeringEvent, Instant capturedAt) {
            return new TransferRecord(
                    t.getId(), t.getTransferNumber(),
                    t.getOriginBranchId(), t.getDestinationBranchId(),
                    t.getStatus().name(), t.isUrgency(), t.getRouteId(),
                    t.getCarrierName(), t.getEstimatedArrivalDate(),
                    t.getRequestedAt(), t.getApprovedAt(), t.getDispatchedAt(), t.getReceivedAt(),
                    items.stream().map(TransferItemRecord::from).toList(),
                    triggeringEvent, capturedAt);
        }
    }

    /** Ítem de transferencia dentro del payload del Data Lake. */
    public record TransferItemRecord(
            Long id,
            Long productId,
            BigDecimal quantityRequested,
            BigDecimal quantityApproved,
            BigDecimal quantityShipped,
            BigDecimal quantityReceived,
            BigDecimal quantityMissing,
            String discrepancyTreatment) {

        public static TransferItemRecord from(TransferItem i) {
            return new TransferItemRecord(
                    i.getId(), i.getProductId(),
                    i.getQuantityRequested(), i.getQuantityApproved(),
                    i.getQuantityShipped(), i.getQuantityReceived(), i.getQuantityMissing(),
                    i.getDiscrepancyTreatment() != null ? i.getDiscrepancyTreatment().name() : null);
        }
    }

    /**
     * Payload completo de una venta en el Data Lake.
     * Incluye cabecera (número, sucursal, vendedor, fecha, totales, descuentos)
     * y todas las líneas (producto, cantidad, precio efectivo cobrado, descuento
     * por línea, total de línea, cantidad devuelta acumulada) para análisis
     * de mercado: tendencias de ventas, productos más vendidos, ingresos por
     * sucursal, efectividad de descuentos, rotación de inventario tecnológico.
     */
    public record SaleRecord(
            Long id,
            String saleNumber,
            Long branchId,
            Long soldByUserId,
            Long priceListId,
            String status,
            Instant saleDate,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal total,
            List<SaleItemRecord> items,
            Instant capturedAt) {

        public static SaleRecord from(Sale s, List<SaleItem> items, Instant capturedAt) {
            return new SaleRecord(
                    s.getId(), s.getSaleNumber(), s.getBranchId(),
                    s.getSoldByUserId(), s.getPriceListId(), s.getStatus().name(),
                    s.getSaleDate(), s.getSubtotal(), s.getDiscountTotal(), s.getTotal(),
                    items.stream().map(SaleItemRecord::from).toList(),
                    capturedAt);
        }
    }

    /** Línea de venta dentro del payload del Data Lake. */
    public record SaleItemRecord(
            Long id,
            Long productId,
            Long unitOfMeasureId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountPercentage,
            BigDecimal lineTotal,
            BigDecimal quantityReturned) {

        public static SaleItemRecord from(SaleItem i) {
            return new SaleItemRecord(
                    i.getId(), i.getProductId(), i.getUnitOfMeasureId(),
                    i.getQuantity(), i.getUnitPrice(), i.getDiscountPercentage(),
                    i.getLineTotal(), i.getQuantityReturned());
        }
    }

    /**
     * Payload completo de una orden de compra en el Data Lake.
     * Incluye cabecera (número, proveedor, sucursal, condiciones comerciales,
     * estado) y todas las líneas (producto, cantidades ordenadas vs. recibidas,
     * costo pactado) para análisis operativo: comportamiento de proveedores,
     * cumplimiento de entregas, análisis de costos de aprovisionamiento por
     * producto y sucursal.
     */
    public record PurchaseOrderRecord(
            Long id,
            String orderNumber,
            Long supplierId,
            Long branchId,
            String status,
            String paymentTerm,
            Instant orderDate,
            Long createdByUserId,
            List<PurchaseOrderItemRecord> items,
            Instant capturedAt) {

        public static PurchaseOrderRecord from(PurchaseOrder o, List<PurchaseOrderItem> items, Instant capturedAt) {
            return new PurchaseOrderRecord(
                    o.getId(), o.getOrderNumber(), o.getSupplierId(), o.getBranchId(),
                    o.getStatus().name(), o.getPaymentTerm(), o.getOrderDate(),
                    o.getCreatedByUserId(),
                    items.stream().map(PurchaseOrderItemRecord::from).toList(),
                    capturedAt);
        }
    }

    /** Línea de orden de compra dentro del payload del Data Lake. */
    public record PurchaseOrderItemRecord(
            Long id,
            Long productId,
            Long unitOfMeasureId,
            BigDecimal quantityOrdered,
            BigDecimal quantityReceived,
            BigDecimal unitPrice,
            BigDecimal discountPercentage,
            BigDecimal lineTotal) {

        public static PurchaseOrderItemRecord from(PurchaseOrderItem i) {
            return new PurchaseOrderItemRecord(
                    i.getId(), i.getProductId(), i.getUnitOfMeasureId(),
                    i.getQuantityOrdered(), i.getQuantityReceived(),
                    i.getUnitPrice(), i.getDiscountPercentage(), i.getLineTotal());
        }
    }
}
