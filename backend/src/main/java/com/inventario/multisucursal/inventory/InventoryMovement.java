package com.inventario.multisucursal.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ledger append-only de movimientos de inventario (docs/DOMAIN_MODEL.md,
 * sección 2.8; ADR-008; BR-001, BR-015, BR-021). No expone ningún método de
 * mutación después de construida — ningún campo se actualiza ni se elimina
 * una vez insertada la fila (BR-021); no extiende {@code Auditable} porque
 * ya tiene su propio {@code responsibleUserId}/{@code occurredAt}, que
 * cumplen el mismo propósito de auditoría que {@code createdBy}/
 * {@code createdAt} para un ledger de negocio.
 */
@Entity
@Table(name = "inventory_movement")
public class InventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MovementDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MovementReason reason;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(name = "unit_of_measure_id", nullable = false)
    private Long unitOfMeasureId;

    @Column(name = "responsible_user_id", nullable = false)
    private Long responsibleUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(length = 1000)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "idempotency_key", length = 150)
    private String idempotencyKey;

    @Column(name = "purchase_order_item_id")
    private Long purchaseOrderItemId;

    @Column(name = "sale_item_id")
    private Long saleItemId;

    @Column(name = "transfer_item_id")
    private Long transferItemId;

    protected InventoryMovement() {
        // JPA
    }

    public InventoryMovement(
            Long productId,
            Long branchId,
            MovementDirection direction,
            MovementReason reason,
            BigDecimal quantity,
            Long unitOfMeasureId,
            Long responsibleUserId,
            String notes) {
        this.productId = productId;
        this.branchId = branchId;
        this.direction = direction;
        this.reason = reason;
        this.quantity = quantity;
        this.unitOfMeasureId = unitOfMeasureId;
        this.responsibleUserId = responsibleUserId;
        this.occurredAt = Instant.now();
        this.notes = notes;
        this.createdAt = Instant.now();
    }

    /**
     * Constructor para movimientos de recepción de compra (flujo B; BR-003,
     * BR-004), con la FK documental hacia {@code PurchaseOrderItem} y la
     * clave de idempotencia (categoría 2 — creación repetible,
     * docs/CRITICAL_FLOWS.md, sección 1.1).
     */
    public InventoryMovement(
            Long productId,
            Long branchId,
            MovementDirection direction,
            MovementReason reason,
            BigDecimal quantity,
            Long unitOfMeasureId,
            Long responsibleUserId,
            String notes,
            Long purchaseOrderItemId,
            String idempotencyKey) {
        this(productId, branchId, direction, reason, quantity, unitOfMeasureId, responsibleUserId, notes);
        this.purchaseOrderItemId = purchaseOrderItemId;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Constructor para movimientos de venta (flujo A; RF-017 a RF-021), con
     * la FK documental hacia {@code SaleItem}. Sin clave de idempotencia
     * propia: la idempotencia de una venta se resuelve una sola vez, a nivel
     * de {@code Sale.client_reference_id}, antes de generar ningún
     * movimiento — no hace falta una clave derivada por línea como en la
     * recepción de compra (docs/BUSINESS_RULES.md, BR-029).
     */
    public InventoryMovement(
            Long productId,
            Long branchId,
            MovementDirection direction,
            MovementReason reason,
            BigDecimal quantity,
            Long unitOfMeasureId,
            Long responsibleUserId,
            String notes,
            Long saleItemId) {
        this(productId, branchId, direction, reason, quantity, unitOfMeasureId, responsibleUserId, notes);
        this.saleItemId = saleItemId;
    }

    /**
     * Movimiento de devolución de venta (BR-052), con la FK documental hacia
     * {@code SaleItem} y clave de idempotencia. Expuesto como método de
     * fábrica y no como constructor adicional por la misma razón que
     * {@link #forTransfer}: tendría exactamente la misma firma en tiempo de
     * compilación que el constructor de recepción de compra (dos
     * {@code Long}+{@code String} finales no se distinguen entre sí).
     */
    public static InventoryMovement forSaleReturn(
            Long productId,
            Long branchId,
            MovementDirection direction,
            MovementReason reason,
            BigDecimal quantity,
            Long unitOfMeasureId,
            Long responsibleUserId,
            String notes,
            Long saleItemId,
            String idempotencyKey) {
        InventoryMovement movement = new InventoryMovement(
                productId, branchId, direction, reason, quantity, unitOfMeasureId, responsibleUserId, notes);
        movement.saleItemId = saleItemId;
        movement.idempotencyKey = idempotencyKey;
        return movement;
    }

    /**
     * Movimiento generado por una transferencia (flujos D/E/F): el
     * {@code RETIRO}/{@code TRANSFERENCIA_SALIDA} en la sucursal origen al
     * despachar, y el {@code INGRESO}/{@code TRANSFERENCIA_ENTRADA} en la
     * destino al recibir — ambos enlazados a la misma {@code TransferItem}.
     *
     * <p>Se expone como método de fábrica y no como un constructor más
     * porque tendría exactamente la misma firma que el constructor de venta
     * ({@code ..., Long saleItemId}): dos {@code Long} finales no se
     * distinguen entre sí, y un constructor ambiguo invita a pasar el id
     * equivocado sin que el compilador avise.
     */
    public static InventoryMovement forTransfer(
            Long productId,
            Long branchId,
            MovementDirection direction,
            MovementReason reason,
            BigDecimal quantity,
            Long unitOfMeasureId,
            Long responsibleUserId,
            String notes,
            Long transferItemId) {
        InventoryMovement movement = new InventoryMovement(
                productId, branchId, direction, reason, quantity, unitOfMeasureId, responsibleUserId, notes);
        movement.transferItemId = transferItemId;
        return movement;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getBranchId() {
        return branchId;
    }

    public MovementDirection getDirection() {
        return direction;
    }

    public MovementReason getReason() {
        return reason;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Long getUnitOfMeasureId() {
        return unitOfMeasureId;
    }

    public Long getResponsibleUserId() {
        return responsibleUserId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getPurchaseOrderItemId() {
        return purchaseOrderItemId;
    }

    public Long getSaleItemId() {
        return saleItemId;
    }

    public Long getTransferItemId() {
        return transferItemId;
    }
}
