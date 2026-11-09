package com.inventario.multisucursal.transfers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Transferencia entre sucursales (docs/DOMAIN_MODEL.md, sección 2.18;
 * RF-022 a RF-026).
 *
 * <p>Deliberadamente <b>sin métodos de mutación de estado</b>: toda
 * transición se aplica mediante los {@code UPDATE ... WHERE status = ...}
 * atómicos de {@link TransferRepository}, nunca mutando la entidad en
 * memoria. Esto no es estilo — es lo que hace que dos despachos (o dos
 * recepciones) concurrentes no puedan aplicar ambos su efecto
 * (docs/CRITICAL_FLOWS.md, flujos D/E, "Locking/concurrencia: Categoría 1"),
 * y evita además la clase de bug ya vista dos veces en este proyecto, donde
 * una entidad mutada en memoria queda {@code detached} tras un
 * {@code @Modifying(clearAutomatically = true)} y su cambio se pierde en
 * silencio.
 *
 * <p>El historial de responsables y tiempos vive en las columnas de hito de
 * esta misma tabla ({@code requested_by}/{@code approved_by} +
 * {@code requested_at}/{@code approved_at}/{@code dispatched_at}/
 * {@code received_at}), tal como aprueba docs/DOMAIN_MODEL.md — el modelo
 * aprobado no contempla una tabla de historial de estados aparte, así que no
 * se inventa una.
 */
@Entity
@Table(name = "transfer")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_number", nullable = false, unique = true, length = 30)
    private String transferNumber;

    @Column(name = "origin_branch_id", nullable = false)
    private Long originBranchId;

    @Column(name = "destination_branch_id", nullable = false)
    private Long destinationBranchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(nullable = false)
    private boolean urgency;

    /**
     * Ruta clasificada del par origen-destino, resuelta automáticamente al
     * crear la transferencia (RF-028). Nula si ese par aún no tiene ruta
     * clasificada. Es una conveniencia de visualización: la pertenencia real
     * a una ruta la define el par de sucursales, que no puede desincronizarse
     * (BR-036).
     */
    @Column(name = "route_id")
    private Long routeId;

    @Column(name = "carrier_name", length = 150)
    private String carrierName;

    @Column(name = "estimated_arrival_date")
    private LocalDate estimatedArrivalDate;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "client_reference_id", unique = true, length = 150)
    private String clientReferenceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Transfer() {
        // JPA
    }

    public Transfer(
            String transferNumber,
            Long originBranchId,
            Long destinationBranchId,
            boolean urgency,
            Long requestedByUserId,
            String clientReferenceId,
            Long routeId) {
        this.transferNumber = transferNumber;
        this.originBranchId = originBranchId;
        this.destinationBranchId = destinationBranchId;
        this.status = TransferStatus.REQUESTED;
        this.urgency = urgency;
        this.routeId = routeId;
        this.requestedByUserId = requestedByUserId;
        this.requestedAt = Instant.now();
        this.clientReferenceId = clientReferenceId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTransferNumber() {
        return transferNumber;
    }

    public Long getOriginBranchId() {
        return originBranchId;
    }

    public Long getDestinationBranchId() {
        return destinationBranchId;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public boolean isUrgency() {
        return urgency;
    }

    public Long getRouteId() {
        return routeId;
    }

    public String getCarrierName() {
        return carrierName;
    }

    public LocalDate getEstimatedArrivalDate() {
        return estimatedArrivalDate;
    }

    public Long getRequestedByUserId() {
        return requestedByUserId;
    }

    public Long getApprovedByUserId() {
        return approvedByUserId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getClientReferenceId() {
        return clientReferenceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
