package com.inventario.multisucursal.transfers;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Cada transición de estado es un {@code UPDATE ... WHERE status = <esperado>}:
 * una comparación y escritura atómica que la base de datos serializa. Si
 * devuelve 0 filas, otra solicitud ya aplicó la transición (o el estado
 * nunca fue el esperado) y el servicio responde 409 — así se resuelven, sin
 * bloqueos ni claves de idempotencia, el despacho duplicado, la recepción
 * duplicada y la doble aprobación (docs/CRITICAL_FLOWS.md, sección 1.1,
 * categoría 1).
 *
 * <p>Las transiciones que además fijan datos del hito (aprobador, fecha,
 * transportista) lo hacen <b>en la misma sentencia</b>, no con una mutación
 * posterior de la entidad: si el guard falla, esos datos tampoco se escriben.
 */
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByClientReferenceId(String clientReferenceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Transfer t
               SET t.status = com.inventario.multisucursal.transfers.TransferStatus.APPROVED,
                   t.approvedByUserId = :userId, t.approvedAt = :now
             WHERE t.id = :id AND t.status = com.inventario.multisucursal.transfers.TransferStatus.REQUESTED
            """)
    int markApproved(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Transfer t
               SET t.status = com.inventario.multisucursal.transfers.TransferStatus.REJECTED,
                   t.approvedByUserId = :userId, t.approvedAt = :now
             WHERE t.id = :id AND t.status = com.inventario.multisucursal.transfers.TransferStatus.REQUESTED
            """)
    int markRejected(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Transfer t
               SET t.status = com.inventario.multisucursal.transfers.TransferStatus.IN_TRANSIT,
                   t.carrierName = :carrierName, t.estimatedArrivalDate = :estimatedArrivalDate, t.dispatchedAt = :now
             WHERE t.id = :id AND t.status = com.inventario.multisucursal.transfers.TransferStatus.APPROVED
            """)
    int markDispatched(
            @Param("id") Long id,
            @Param("carrierName") String carrierName,
            @Param("estimatedArrivalDate") LocalDate estimatedArrivalDate,
            @Param("now") Instant now);

    /** Cierre de la recepción: solo cuando todas las líneas quedaron atendidas (docs/API_DESIGN.md, sección 7.9). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Transfer t
               SET t.status = :newStatus, t.receivedAt = :now
             WHERE t.id = :id AND t.status = com.inventario.multisucursal.transfers.TransferStatus.IN_TRANSIT
            """)
    int markReceived(@Param("id") Long id, @Param("newStatus") TransferStatus newStatus, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Transfer t
               SET t.status = com.inventario.multisucursal.transfers.TransferStatus.CLOSED
             WHERE t.id = :id AND t.status = com.inventario.multisucursal.transfers.TransferStatus.RECEIVED_PARTIAL
            """)
    int markClosed(@Param("id") Long id);

    /**
     * Base del reporte de cumplimiento logístico (RF-027, RF-030): solo
     * transferencias efectivamente despachadas — una transferencia sin
     * despacho no tiene tiempo de entrega que medir y no puede contarse ni
     * como cumplida ni como incumplida.
     *
     * <p>El filtro por ruta llega resuelto como par origen-destino, no como
     * {@code route_id}: ese par es la identidad de la ruta (UNIQUE en la
     * tabla {@code route}) y no puede quedar desincronizado, así que el
     * reporte incluye también las transferencias creadas antes de que su
     * ruta fuera clasificada (BR-036).
     *
     * <p>{@code dispatchedFrom}/{@code dispatchedTo} nunca llegan nulos — el
     * servicio les da límites amplios por defecto, por la misma razón ya
     * documentada en {@code InventoryMovementRepository}: PostgreSQL no puede
     * inferir el tipo de un {@code Instant} nulo dentro de una comparación.
     */
    @Query("""
            SELECT t FROM Transfer t
             WHERE t.dispatchedAt IS NOT NULL
               AND t.dispatchedAt >= :dispatchedFrom AND t.dispatchedAt <= :dispatchedTo
               AND (:branchId IS NULL OR t.originBranchId = :branchId OR t.destinationBranchId = :branchId)
               AND (:originBranchId IS NULL OR t.originBranchId = :originBranchId)
               AND (:destinationBranchId IS NULL OR t.destinationBranchId = :destinationBranchId)
             ORDER BY t.dispatchedAt DESC
            """)
    List<Transfer> findDispatchedForCompliance(
            @Param("branchId") Long branchId,
            @Param("originBranchId") Long originBranchId,
            @Param("destinationBranchId") Long destinationBranchId,
            @Param("dispatchedFrom") Instant dispatchedFrom,
            @Param("dispatchedTo") Instant dispatchedTo);

    /**
     * Lectura: una transferencia es visible desde su sucursal origen o su
     * destino (docs/API_DESIGN.md, sección 6). {@code branchId} nulo (solo
     * ADMIN llega así) no filtra por sucursal.
     */
    @Query("""
            SELECT t FROM Transfer t
             WHERE (:branchId IS NULL
                        OR (:role IS NULL AND (t.originBranchId = :branchId OR t.destinationBranchId = :branchId))
                        OR (:role = 'origin' AND t.originBranchId = :branchId)
                        OR (:role = 'destination' AND t.destinationBranchId = :branchId))
               AND (:status IS NULL OR t.status = :status)
            """)
    Page<Transfer> search(
            @Param("branchId") Long branchId,
            @Param("role") String role,
            @Param("status") TransferStatus status,
            Pageable pageable);

    /**
     * Base del reporte exportable de transferencias (BR-056): misma regla de
     * visibilidad que {@link #search} (origen o destino), acotada además por
     * fecha de solicitud — a diferencia de {@link #search}, que no filtra por
     * fecha porque una página de la UI no necesita acotar cuánto trae.
     */
    @Query("""
            SELECT t FROM Transfer t
             WHERE (:branchId IS NULL OR t.originBranchId = :branchId OR t.destinationBranchId = :branchId)
               AND (:status IS NULL OR t.status = :status)
               AND t.requestedAt >= :dateFrom AND t.requestedAt <= :dateTo
             ORDER BY t.requestedAt DESC
            """)
    Page<Transfer> searchForReport(
            @Param("branchId") Long branchId,
            @Param("status") TransferStatus status,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            Pageable pageable);

    /**
     * Transferencias activas de una sucursal —origen o destino— para el
     * dashboard (BR-041, RF-033). "Activa" = cualquier estado no terminal de
     * la máquina de estados de BR-020: excluye {@code REJECTED},
     * {@code RECEIVED_COMPLETE} y {@code CLOSED}.
     */
    @Query("""
            SELECT t FROM Transfer t
             WHERE (t.originBranchId = :branchId OR t.destinationBranchId = :branchId)
               AND t.status IN (
                    com.inventario.multisucursal.transfers.TransferStatus.REQUESTED,
                    com.inventario.multisucursal.transfers.TransferStatus.APPROVED,
                    com.inventario.multisucursal.transfers.TransferStatus.IN_TRANSIT,
                    com.inventario.multisucursal.transfers.TransferStatus.RECEIVED_PARTIAL)
             ORDER BY t.requestedAt DESC
            """)
    List<Transfer> findActive(@Param("branchId") Long branchId);
}
