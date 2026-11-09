package com.inventario.multisucursal.inventory;

import com.inventario.multisucursal.common.web.PageResponse;
import com.inventario.multisucursal.events.DomainEvent;
import com.inventario.multisucursal.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Alertas de stock mínimo (BR-010, UC-16, RF-010/RF-036 — funcionalidad
 * adicional elegida). Ver docs/adr/ADR-015-alertas-de-stock-minimo.md para el
 * diseño completo (condición de disparo, deduplicación, alcance, estados).
 *
 * <p>{@link #evaluate} lo invoca cada operación que modifica
 * {@code Inventory.quantityOnHand} —venta, ajuste, recepción de compra,
 * despacho/recepción de transferencia— <b>dentro de su propia transacción</b>
 * (BR-010), justo después de que su propio `UPDATE` condicionado por
 * {@code version} tuvo éxito. `inventory` ya es una dependencia existente de
 * `sales`/`purchases`/`transfers` (importan {@code Inventory}/
 * {@code InventoryRepository} directamente); vivir aquí no añade ninguna
 * arista nueva al grafo de dependencias.
 */
@Service
public class StockAlertService {

    private static final Logger log = LoggerFactory.getLogger(StockAlertService.class);

    private final StockAlertRepository stockAlertRepository;
    private final DomainEventPublisher eventPublisher;

    public StockAlertService(StockAlertRepository stockAlertRepository, DomainEventPublisher eventPublisher) {
        this.stockAlertRepository = stockAlertRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Nunca debe poder tumbar la operación de negocio que la invoca —una
     * venta o una compra ya confirmadas no se revierten porque falle su
     * notificación de stock—, así que cualquier fallo inesperado se registra
     * y se descarta aquí mismo, nunca se propaga. La comprobación
     * "¿ya existe una alerta activa?" antes de insertar es, en la práctica,
     * suficiente para evitar duplicados sin necesitar capturar una
     * violación de índice único dentro de la misma transacción: todo
     * llamador ya serializa sus escrituras sobre el mismo `Inventory` vía su
     * propio bloqueo optimista por `version` (docs/CRITICAL_FLOWS.md, sección
     * 1.2), así que dos evaluaciones sobre el mismo `inventory_id` nunca
     * corren de verdad en paralelo — el índice único parcial de
     * `stock_alert` (BR-010) queda como respaldo de última línea a nivel de
     * base de datos, igual criterio que `existsByEmail`/`existsByCode` en
     * `users`/`branches`.
     */
    @Transactional
    public void evaluate(Long inventoryId, Long branchId, Long productId, BigDecimal quantityOnHand, BigDecimal minimumStock) {
        try {
            if (quantityOnHand.compareTo(minimumStock) <= 0) {
                if (!stockAlertRepository.existsByInventoryIdAndStatus(inventoryId, StockAlertStatus.ACTIVE)) {
                    stockAlertRepository.save(new StockAlert(inventoryId));
                    eventPublisher.publish(DomainEvent.stockAlertTriggered(branchId, productId));
                }
            } else {
                int resolved = stockAlertRepository.resolveActive(inventoryId, Instant.now());
                if (resolved > 0) {
                    eventPublisher.publish(DomainEvent.stockAlertResolved(branchId, productId));
                }
            }
        } catch (RuntimeException unexpected) {
            log.error("No se pudo evaluar la alerta de stock mínimo para inventory_id={}", inventoryId, unexpected);
        }
    }

    public PageResponse<StockAlertResponse> list(Long branchId, StockAlertStatus status, Pageable pageable) {
        return PageResponse.from(stockAlertRepository.search(branchId, status, pageable).map(StockAlertResponse::from));
    }
}
