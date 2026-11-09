package com.inventario.multisucursal.sales;

import com.inventario.multisucursal.auth.AuthorizationService;
import com.inventario.multisucursal.common.exception.BadRequestException;
import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.events.DomainEvent;
import com.inventario.multisucursal.events.DomainEventPublisher;
import com.inventario.multisucursal.inventory.Inventory;
import com.inventario.multisucursal.inventory.InventoryMovement;
import com.inventario.multisucursal.inventory.InventoryMovementRepository;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.inventory.MovementDirection;
import com.inventario.multisucursal.inventory.MovementReason;
import com.inventario.multisucursal.inventory.StockAlertService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Devolución de venta (BR-052): repone al inventario lo que un cliente
 * devuelve de una venta ya confirmada. Mismo patrón que
 * {@code PurchaseReceiptService} — un {@code POST} puede traer varias
 * líneas, cada una se valida y aplica de forma independiente, y la
 * idempotencia (categoría 2) se resuelve por línea derivando la clave de
 * {@code InventoryMovement.idempotency_key} como
 * {@code <Idempotency-Key>:<saleItemId>}.
 *
 * <p>No recalcula {@code Sale.subtotal}/{@code discountTotal}/{@code total}
 * ni cambia {@code Sale.status}: el comprobante original queda intacto como
 * registro histórico de lo vendido (BR-021); la trazabilidad de la
 * devolución vive en {@code SaleItem.quantityReturned}/{@code pending} y en
 * el propio {@code InventoryMovement} (BR-052, alcance explícito — no se
 * pidió una nota de crédito ni una reversión monetaria, solo reponer stock).
 * Tampoco recalcula {@code Inventory.averageUnitCost}: el producto vuelve al
 * costo promedio ya vigente, a diferencia de una recepción de compra.
 */
@Service
public class SaleReturnService {

    private static final int MAX_RETRIES = 3;

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryMovementRepository movementRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuthorizationService authorizationService;
    private final StockAlertService stockAlertService;

    public SaleReturnService(
            SaleRepository saleRepository,
            SaleItemRepository saleItemRepository,
            InventoryRepository inventoryRepository,
            InventoryMovementRepository movementRepository,
            DomainEventPublisher eventPublisher,
            AuthorizationService authorizationService,
            StockAlertService stockAlertService) {
        this.saleRepository = saleRepository;
        this.saleItemRepository = saleItemRepository;
        this.inventoryRepository = inventoryRepository;
        this.movementRepository = movementRepository;
        this.eventPublisher = eventPublisher;
        this.authorizationService = authorizationService;
        this.stockAlertService = stockAlertService;
    }

    @Transactional
    public SaleReturnResponse createReturn(Long saleId, SaleReturnRequest request, String idempotencyKey, Long responsibleUserId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUERIDO", "El encabezado Idempotency-Key es obligatorio.");
        }

        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("VENTA_NO_ENCONTRADA", "Venta no encontrada."));
        authorizationService.requireBranchAccess(sale.getBranchId());

        List<SaleReturnResponse.ReturnedItem> returnedItems = new ArrayList<>();
        List<SaleReturnResponse.InventoryUpdate> inventoryUpdates = new ArrayList<>();

        for (SaleReturnItemRequest itemRequest : request.items()) {
            String derivedKey = idempotencyKey + ":" + itemRequest.saleItemId();
            Optional<InventoryMovement> existingMovement = movementRepository.findByIdempotencyKey(derivedKey);

            SaleItem item;
            Inventory inventory;
            if (existingMovement.isPresent()) {
                // Reintento de esta misma línea: no-op, se reutiliza el resultado ya
                // aplicado — mismo criterio que PurchaseReceiptService (la comprobación
                // de idempotencia va antes que cualquier otra validación de negocio).
                item = findItemOrThrow(itemRequest.saleItemId(), sale.getId());
                inventory = inventoryRepository.findByProductIdAndBranchId(item.getProductId(), sale.getBranchId())
                        .orElseThrow(() -> new IllegalStateException("Inventario inconsistente: movimiento existente sin fila de inventario."));
            } else {
                if (itemRequest.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessRuleViolationException("CANTIDAD_INVALIDA", "La cantidad a devolver debe ser mayor que cero.");
                }
                item = applyItemReturn(itemRequest.saleItemId(), sale.getId(), itemRequest.quantity());
                inventory = applyInventoryReturn(item.getProductId(), sale.getBranchId(), itemRequest.quantity());
                movementRepository.save(InventoryMovement.forSaleReturn(
                        item.getProductId(),
                        sale.getBranchId(),
                        MovementDirection.INGRESO,
                        MovementReason.DEVOLUCION,
                        itemRequest.quantity(),
                        item.getUnitOfMeasureId(),
                        responsibleUserId,
                        null,
                        item.getId(),
                        derivedKey));
                eventPublisher.publish(DomainEvent.inventoryUpdated(sale.getBranchId(), item.getProductId()));
            }

            returnedItems.add(new SaleReturnResponse.ReturnedItem(
                    String.valueOf(item.getId()), item.getQuantity(), item.getQuantityReturned(), item.pending()));
            inventoryUpdates.add(new SaleReturnResponse.InventoryUpdate(
                    String.valueOf(item.getProductId()), String.valueOf(sale.getBranchId()), inventory.getQuantityOnHand()));
        }

        return new SaleReturnResponse(String.valueOf(sale.getId()), returnedItems, inventoryUpdates);
    }

    /** BR-052: bloqueo optimista con reintento sobre {@code SaleItem.version}. */
    private SaleItem applyItemReturn(Long itemId, Long expectedSaleId, BigDecimal quantity) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            SaleItem current = findItemOrThrow(itemId, expectedSaleId);
            BigDecimal pending = current.pending();
            if (quantity.compareTo(pending) > 0) {
                throw new BusinessRuleViolationException(
                        "CANTIDAD_DEVOLUCION_EXCEDE_VENDIDO",
                        "La cantidad a devolver (" + quantity + ") excede lo pendiente de devolver (" + pending + ").");
            }
            BigDecimal newQuantityReturned = current.getQuantityReturned().add(quantity);
            int updated = saleItemRepository.applyReturn(current.getId(), current.getVersion(), newQuantityReturned);
            if (updated == 1) {
                return findItemOrThrow(itemId, expectedSaleId);
            }
        }
        throw new ResourceConflictException("CONFLICTO_CONCURRENCIA", "No se pudo registrar la devolución tras varios intentos por alta concurrencia.");
    }

    /**
     * BR-022 aplicado a la devolución: repone cantidad al costo promedio ya
     * vigente (no lo recalcula) — a diferencia de una recepción de compra.
     * El producto ya fue vendido desde esta sucursal, así que la fila de
     * {@code Inventory} está garantizada a existir (mismo criterio que
     * {@code SaleService.applyWithdrawal}, en sentido inverso).
     */
    private Inventory applyInventoryReturn(Long productId, Long branchId, BigDecimal quantityBase) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Inventory inventory = inventoryRepository.findByProductIdAndBranchId(productId, branchId)
                    .orElseThrow(() -> new IllegalStateException("Inventario inconsistente: se devuelve un producto sin fila de inventario en la sucursal."));
            BigDecimal newQuantity = inventory.getQuantityOnHand().add(quantityBase);
            int updated = inventoryRepository.applyQuantity(inventory.getId(), inventory.getVersion(), newQuantity, Instant.now());
            if (updated == 1) {
                stockAlertService.evaluate(inventory.getId(), branchId, productId, newQuantity, inventory.getMinimumStock());
                return inventoryRepository.findByProductIdAndBranchId(productId, branchId).orElseThrow();
            }
        }
        throw new ResourceConflictException("CONFLICTO_CONCURRENCIA", "No se pudo actualizar el inventario tras varios intentos por alta concurrencia.");
    }

    private SaleItem findItemOrThrow(Long itemId, Long expectedSaleId) {
        SaleItem item = saleItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("LINEA_VENTA_NO_ENCONTRADA", "Línea de venta no encontrada."));
        if (!item.getSaleId().equals(expectedSaleId)) {
            throw new ResourceNotFoundException("LINEA_VENTA_NO_ENCONTRADA", "La línea no pertenece a esta venta.");
        }
        return item;
    }
}
