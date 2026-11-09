package com.inventario.multisucursal.logistics;

import com.inventario.multisucursal.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Ruta clasificada entre dos sucursales (docs/DOMAIN_MODEL.md, sección 2.17;
 * RF-028).
 *
 * <p>El par origen-destino es su clave de negocio e <b>inmutable</b>: cambiar
 * el par no sería reclasificar esta ruta, sería otra ruta distinta. Lo único
 * editable es la clasificación (docs/API_DESIGN.md, sección 7.9:
 * {@code PATCH /routes/{id}} "actualiza clasificación").
 *
 * <p>Deliberadamente <b>no</b> guarda transportista ni fechas: esa decisión ya
 * está tomada en docs/DOMAIN_MODEL.md 2.17 — no existe una entidad
 * {@code Shipment} separada, y los datos de envío viven en {@code Transfer},
 * que es donde ocurren. Duplicarlos aquí crearía justamente las dos fuentes
 * de verdad incoherentes que el diseño evita.
 */
@Entity
@Table(name = "route", uniqueConstraints = @UniqueConstraint(columnNames = {"origin_branch_id", "destination_branch_id"}))
public class Route extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "origin_branch_id", nullable = false)
    private Long originBranchId;

    @Column(name = "destination_branch_id", nullable = false)
    private Long destinationBranchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RouteClassification classification;

    protected Route() {
        // JPA
    }

    public Route(Long originBranchId, Long destinationBranchId, RouteClassification classification) {
        this.originBranchId = originBranchId;
        this.destinationBranchId = destinationBranchId;
        this.classification = classification;
    }

    public void reclassify(RouteClassification classification) {
        this.classification = classification;
    }

    public Long getId() {
        return id;
    }

    public Long getOriginBranchId() {
        return originBranchId;
    }

    public Long getDestinationBranchId() {
        return destinationBranchId;
    }

    public RouteClassification getClassification() {
        return classification;
    }
}
