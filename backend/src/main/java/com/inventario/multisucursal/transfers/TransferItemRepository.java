package com.inventario.multisucursal.transfers;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Cada etapa de una línea se aplica con un {@code UPDATE ... WHERE <columna>
 * IS NULL}: la nulidad de la columna es la guarda de idempotencia por línea.
 * 0 filas afectadas significa "esa etapa ya se registró" → 409, sin efecto
 * doble (docs/CRITICAL_FLOWS.md, flujos D/E/F).
 */
public interface TransferItemRepository extends JpaRepository<TransferItem, Long> {

    List<TransferItem> findByTransferId(Long transferId);

    /** Evita N+1 al calcular el impacto de varias transferencias activas a la vez (BR-041). */
    List<TransferItem> findByTransferIdIn(Collection<Long> transferIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TransferItem i SET i.quantityApproved = :quantityApproved
             WHERE i.id = :id AND i.quantityApproved IS NULL
            """)
    int markApproved(@Param("id") Long id, @Param("quantityApproved") BigDecimal quantityApproved);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TransferItem i SET i.quantityShipped = :quantityShipped
             WHERE i.id = :id AND i.quantityShipped IS NULL
            """)
    int markShipped(@Param("id") Long id, @Param("quantityShipped") BigDecimal quantityShipped);

    /** {@code quantityMissing} queda nulo si la recepción fue completa (docs/API_DESIGN.md, ejemplo 9.6). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TransferItem i SET i.quantityReceived = :quantityReceived, i.quantityMissing = :quantityMissing
             WHERE i.id = :id AND i.quantityReceived IS NULL
            """)
    int markReceived(
            @Param("id") Long id,
            @Param("quantityReceived") BigDecimal quantityReceived,
            @Param("quantityMissing") BigDecimal quantityMissing);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TransferItem i
               SET i.discrepancyTreatment = :treatment, i.treatmentByUserId = :userId,
                   i.treatmentAt = :now, i.followUpTransferId = :followUpTransferId, i.treatmentNotes = :notes
             WHERE i.id = :id AND i.discrepancyTreatment IS NULL
            """)
    int markTreated(
            @Param("id") Long id,
            @Param("treatment") DiscrepancyTreatment treatment,
            @Param("userId") Long userId,
            @Param("now") Instant now,
            @Param("followUpTransferId") Long followUpTransferId,
            @Param("notes") String notes);
}
