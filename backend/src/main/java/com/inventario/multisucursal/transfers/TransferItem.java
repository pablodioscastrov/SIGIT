package com.inventario.multisucursal.transfers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Línea de transferencia (docs/DOMAIN_MODEL.md, sección 2.19). Las
 * cantidades se pueblan por etapas; una columna nula significa "esa etapa
 * todavía no ocurrió" y es, además, la guarda de idempotencia por línea
 * (categoría 1): recibir dos veces la misma línea falla porque
 * {@code quantityReceived} ya no es nula.
 *
 * <p>Como {@link Transfer}, no expone mutadores: cada etapa se aplica con un
 * {@code UPDATE ... WHERE <columna> IS NULL} atómico en
 * {@link TransferItemRepository}.
 */
@Entity
@Table(name = "transfer_item", uniqueConstraints = @UniqueConstraint(columnNames = {"transfer_id", "product_id"}))
public class TransferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "unit_of_measure_id", nullable = false)
    private Long unitOfMeasureId;

    @Column(name = "quantity_requested", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantityRequested;

    @Column(name = "quantity_approved", precision = 19, scale = 6)
    private BigDecimal quantityApproved;

    @Column(name = "quantity_shipped", precision = 19, scale = 6)
    private BigDecimal quantityShipped;

    @Column(name = "quantity_received", precision = 19, scale = 6)
    private BigDecimal quantityReceived;

    @Column(name = "quantity_missing", precision = 19, scale = 6)
    private BigDecimal quantityMissing;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_treatment", length = 20)
    private DiscrepancyTreatment discrepancyTreatment;

    @Column(name = "treatment_by_user_id")
    private Long treatmentByUserId;

    @Column(name = "treatment_at")
    private Instant treatmentAt;

    @Column(name = "follow_up_transfer_id")
    private Long followUpTransferId;

    @Column(name = "treatment_notes", length = 1000)
    private String treatmentNotes;

    protected TransferItem() {
        // JPA
    }

    public TransferItem(Long transferId, Long productId, Long unitOfMeasureId, BigDecimal quantityRequested) {
        this.transferId = transferId;
        this.productId = productId;
        this.unitOfMeasureId = unitOfMeasureId;
        this.quantityRequested = quantityRequested;
    }

    /** Una línea tiene faltante pendiente de tratar (docs/CRITICAL_FLOWS.md, flujo F1). */
    public boolean hasUntreatedShortage() {
        return quantityMissing != null
                && quantityMissing.compareTo(BigDecimal.ZERO) > 0
                && discrepancyTreatment == null;
    }

    public Long getId() {
        return id;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getUnitOfMeasureId() {
        return unitOfMeasureId;
    }

    public BigDecimal getQuantityRequested() {
        return quantityRequested;
    }

    public BigDecimal getQuantityApproved() {
        return quantityApproved;
    }

    public BigDecimal getQuantityShipped() {
        return quantityShipped;
    }

    public BigDecimal getQuantityReceived() {
        return quantityReceived;
    }

    public BigDecimal getQuantityMissing() {
        return quantityMissing;
    }

    public DiscrepancyTreatment getDiscrepancyTreatment() {
        return discrepancyTreatment;
    }

    public Long getTreatmentByUserId() {
        return treatmentByUserId;
    }

    public Instant getTreatmentAt() {
        return treatmentAt;
    }

    public Long getFollowUpTransferId() {
        return followUpTransferId;
    }

    public String getTreatmentNotes() {
        return treatmentNotes;
    }
}
