package com.inventario.multisucursal.purchases;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.ProductUnit;
import com.inventario.multisucursal.products.ProductUnitRepository;

/**
 * Recepción de compra (flujo B, docs/CRITICAL_FLOWS.md; RF-014, RF-016;
 * BR-003, BR-004, BR-016). Transacción única que cubre: validar → registrar
 * recepción/estado → calcular costo → incrementar stock → registrar
 * movimiento → commit — si cualquier paso falla, {@code @Transactional}
 * revierte todo (ningún paso hace commit parcial, BR-016).
 *
 * <p>Un {@code POST /purchase-orders/{id}/receipts} puede traer varias
 * líneas; cada línea se procesa con el mismo orden de pasos del pseudocódigo
 * de flujo B, y solo al final se recalcula el estado agregado de la orden.
 * La idempotencia (categoría 2) se resuelve por línea, derivando la clave
 * única de {@code InventoryMovement.idempotency_key} como
 * {@code <Idempotency-Key>:<purchaseOrderItemId>} — el header es uno solo
 * por solicitud, pero la restricción de unicidad es por movimiento; esto
 * permite reintentar la solicitud completa (todas las líneas ya aplicadas
 * se detectan y no se reaplican) sin invalidar el mismo header en una
 * solicitud legítima distinta a otra línea de la misma orden.
 */
@Service
public class PurchaseReceiptService {

    private static final int MAX_RETRIES = 3;
    private static final int QUANTITY_SCALE = 6;
    private static final int AVERAGE_COST_SCALE = 6;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    /**
     * Auditoría de seguridad: el costo unitario de la recepción puede
     * diferir legítimamente del pactado en la orden (BR-028), pero no tenía
     * ningún límite — se comprobó en vivo que un valor absurdo (p. ej. un
     * cero de más por error de tecleo) corrompe permanentemente el costo
     * promedio ponderado de todo el stock del producto en la sucursal, sin
     * ningún mecanismo para revertirlo. Tolerancia amplia y provisional (3×
     * hacia arriba o hacia abajo) para no bloquear una fluctuación real de
     * precio; el umbral exacto es una decisión de negocio pendiente de
     * confirmar, no algo que este cambio decida por sí solo.
     */
    private static final BigDecimal MAX_RECEIPT_PRICE_DEVIATION_FACTOR = new BigDecimal("3");

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryMovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final ProductUnitRepository productUnitRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuthorizationService authorizationService;
    private final StockAlertService stockAlertService;

    public PurchaseReceiptService(
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderItemRepository purchaseOrderItemRepository,
            InventoryRepository inventoryRepository,
            InventoryMovementRepository movementRepository,
            ProductRepository productRepository,
            ProductUnitRepository productUnitRepository,
            DomainEventPublisher eventPublisher,
            AuthorizationService authorizationService,
            StockAlertService stockAlertService) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.inventoryRepository = inventoryRepository;
        this.movementRepository = movementRepository;
        this.productRepository = productRepository;
        this.productUnitRepository = productUnitRepository;
        this.eventPublisher = eventPublisher;
        this.authorizationService = authorizationService;
        this.stockAlertService = stockAlertService;
    }

    @Transactional
    public PurchaseReceiptResponse receive(Long purchaseOrderId, PurchaseReceiptRequest request, String idempotencyKey, Long responsibleUserId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUERIDO", "El encabezado Idempotency-Key es obligatorio.");
        }

        PurchaseOrder order = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ORDEN_COMPRA_NO_ENCONTRADA", "Orden de compra no encontrada."));
        authorizationService.requireBranchAccess(order.getBranchId());

        List<PurchaseReceiptResponse.ReceivedItem> receivedItems = new ArrayList<>();
        List<PurchaseReceiptResponse.InventoryUpdate> inventoryUpdates = new ArrayList<>();

        // Orden determinista por producto, no el orden de llegada del payload
        // ni el de las líneas de la orden — mismo motivo que
        // SaleService.confirmSale: dos transacciones concurrentes que tocan
        // las mismas dos filas de Inventory en orden opuesto pueden producir
        // un deadlock real de base de datos, no solo un reintento optimista
        // (ver SaleOppositeOrderConcurrencyTest, docs/TEST_STRATEGY.md).
        // Ordenar por productId (resuelto de antemano, sin N+1 dentro del
        // bucle) garantiza que cualquier transacción que toque los mismos
        // productos los bloquee siempre en la misma secuencia.
        Map<Long, PurchaseOrderItem> itemsById = purchaseOrderItemRepository
                .findAllById(request.items().stream().map(ReceiptItemRequest::purchaseOrderItemId).toList())
                .stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, item -> item));
        List<ReceiptItemRequest> orderedItems = request.items().stream()
                .sorted(Comparator.comparing(request0 -> {
                    PurchaseOrderItem resolved = itemsById.get(request0.purchaseOrderItemId());
                    return resolved != null ? resolved.getProductId() : Long.MAX_VALUE;
                }))
                .toList();

        for (ReceiptItemRequest itemRequest : orderedItems) {
            String derivedKey = idempotencyKey + ":" + itemRequest.purchaseOrderItemId();
            Optional<InventoryMovement> existingMovement = movementRepository.findByIdempotencyKey(derivedKey);

            PurchaseOrderItem item;
            Inventory inventory;
            if (existingMovement.isPresent()) {
                // Reintento de esta misma línea: no-op, se reutiliza el resultado ya aplicado
                // (docs/CRITICAL_FLOWS.md, flujo B) — la comprobación de idempotencia va
                // ANTES que la de estado de la orden, igual que en el pseudocódigo aprobado:
                // un reintento legítimo debe replicar el resultado incluso si la orden ya
                // quedó RECEIVED como efecto de ese mismo envío original.
                item = findItemOrThrow(itemRequest.purchaseOrderItemId(), order.getId());
                inventory = inventoryRepository.findByProductIdAndBranchId(item.getProductId(), order.getBranchId())
                        .orElseThrow(() -> new IllegalStateException("Inventario inconsistente: movimiento existente sin fila de inventario."));
            } else {
                if (order.getStatus() == PurchaseOrderStatus.RECEIVED || order.getStatus() == PurchaseOrderStatus.CANCELLED) {
                    throw new ResourceConflictException("ORDEN_YA_RECIBIDA", "La orden ya está recibida o cancelada.");
                }
                if (itemRequest.quantityReceived().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessRuleViolationException("CANTIDAD_INVALIDA", "La cantidad recibida debe ser mayor que cero.");
                }
                requireReceiptPriceWithinRange(itemsById.get(itemRequest.purchaseOrderItemId()), itemRequest.unitPrice());
                item = applyItemReceipt(itemRequest.purchaseOrderItemId(), order.getId(), itemRequest.quantityReceived());
                inventory = applyInventoryReceipt(item.getProductId(), order.getBranchId(), item.getUnitOfMeasureId(), itemRequest.quantityReceived(), itemRequest.unitPrice());
                movementRepository.save(new InventoryMovement(
                        item.getProductId(),
                        order.getBranchId(),
                        MovementDirection.INGRESO,
                        MovementReason.COMPRA,
                        itemRequest.quantityReceived(),
                        item.getUnitOfMeasureId(),
                        responsibleUserId,
                        null,
                        item.getId(),
                        derivedKey));
                eventPublisher.publish(DomainEvent.inventoryUpdated(order.getBranchId(), item.getProductId()));
            }

            receivedItems.add(new PurchaseReceiptResponse.ReceivedItem(
                    String.valueOf(item.getId()), item.getQuantityOrdered(), item.getQuantityReceived(), item.pending()));
            inventoryUpdates.add(new PurchaseReceiptResponse.InventoryUpdate(
                    String.valueOf(item.getProductId()), String.valueOf(order.getBranchId()), inventory.getQuantityOnHand(), inventory.getAverageUnitCost()));
        }

        boolean allLinesComplete = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId()).stream()
                .allMatch(i -> i.getQuantityReceived().compareTo(i.getQuantityOrdered()) >= 0);
        order.updateStatus(allLinesComplete ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED);
        // `order` quedó detached: los UPDATE atómicos de más arriba (applyReceipt,
        // @Modifying(clearAutomatically = true)) vacían el contexto de persistencia
        // en cada intento, así que el dirty-checking implícito no alcanza a
        // persistir este último cambio de estado — hay que guardarlo explícitamente.
        order = purchaseOrderRepository.save(order);

        // Notifica al Data Lake que la recepción está completa con el estado
        // actualizado de la orden (PARTIALLY_RECEIVED o RECEIVED).
        eventPublisher.publish(DomainEvent.purchaseOrderReceived(order.getId(), order.getBranchId()));

        return new PurchaseReceiptResponse(String.valueOf(order.getId()), order.getStatus(), receivedItems, inventoryUpdates);
    }

    /**
     * Auditoría de seguridad: sin este límite, un costo de recepción
     * absurdo (fuera de un múltiplo razonable del pactado) corrompe
     * permanentemente {@code Inventory.averageUnitCost} — ver el javadoc de
     * {@link #MAX_RECEIPT_PRICE_DEVIATION_FACTOR}. Si {@code item} es
     * {@code null} (línea inexistente), no valida nada aquí: el 404 real lo
     * lanza {@link #findItemOrThrow} inmediatamente después, en
     * {@link #applyItemReceipt}.
     */
    private void requireReceiptPriceWithinRange(PurchaseOrderItem item, BigDecimal receivedUnitPrice) {
        if (item == null) {
            return;
        }
        BigDecimal agreedUnitPrice = item.getUnitPrice();
        BigDecimal upperBound = agreedUnitPrice.multiply(MAX_RECEIPT_PRICE_DEVIATION_FACTOR);
        BigDecimal lowerBound = agreedUnitPrice.divide(MAX_RECEIPT_PRICE_DEVIATION_FACTOR, AVERAGE_COST_SCALE, ROUNDING);
        if (receivedUnitPrice.compareTo(upperBound) > 0 || receivedUnitPrice.compareTo(lowerBound) < 0) {
            throw new BusinessRuleViolationException(
                    "PRECIO_RECEPCION_FUERA_DE_RANGO",
                    "El precio de recepción (" + receivedUnitPrice + ") se aleja demasiado del pactado en la orden ("
                            + agreedUnitPrice + "). Verifica el valor o registra la recepción con el precio correcto.");
        }
    }

    /** BR-022/BR-017 aplicado a {@code PurchaseOrderItem.version} (docs/CRITICAL_FLOWS.md, flujo B). */
    private PurchaseOrderItem applyItemReceipt(Long itemId, Long expectedOrderId, BigDecimal quantityReceived) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            PurchaseOrderItem current = findItemOrThrow(itemId, expectedOrderId);
            BigDecimal pending = current.pending();
            if (quantityReceived.compareTo(pending) > 0) {
                throw new BusinessRuleViolationException(
                        "CANTIDAD_RECEPCION_EXCEDE_ORDENADO",
                        "La cantidad a recibir (" + quantityReceived + ") excede lo pendiente (" + pending + ").");
            }
            BigDecimal newQuantityReceived = current.getQuantityReceived().add(quantityReceived);
            int updated = purchaseOrderItemRepository.applyReceipt(current.getId(), current.getVersion(), newQuantityReceived);
            if (updated == 1) {
                return findItemOrThrow(itemId, expectedOrderId);
            }
        }
        throw new ResourceConflictException("CONFLICTO_CONCURRENCIA", "No se pudo registrar la recepción tras varios intentos por alta concurrencia.");
    }

    /** BR-004/BR-016: costo promedio ponderado, atómico junto con el incremento de stock. */
    private Inventory applyInventoryReceipt(Long productId, Long branchId, Long unitOfMeasureId, BigDecimal quantityReceived, BigDecimal unitPrice) {
        BigDecimal conversionFactor = resolveConversionFactor(productId, unitOfMeasureId);
        BigDecimal quantityBase = quantityReceived.multiply(conversionFactor).setScale(QUANTITY_SCALE, ROUNDING);
        BigDecimal unitPriceBase = conversionFactor.compareTo(BigDecimal.ONE) == 0
                ? unitPrice
                : unitPrice.divide(conversionFactor, AVERAGE_COST_SCALE, ROUNDING);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Inventory inventory = findOrCreateInventory(productId, branchId);
            BigDecimal currentQuantity = inventory.getQuantityOnHand();
            BigDecimal currentCost = inventory.getAverageUnitCost();
            BigDecimal newQuantity = currentQuantity.add(quantityBase);
            BigDecimal numerator = currentQuantity.multiply(currentCost).add(quantityBase.multiply(unitPriceBase));
            BigDecimal newCost = numerator.divide(newQuantity, AVERAGE_COST_SCALE, ROUNDING);

            int updated = inventoryRepository.applyReceipt(inventory.getId(), inventory.getVersion(), newQuantity, newCost, Instant.now());
            if (updated == 1) {
                stockAlertService.evaluate(inventory.getId(), branchId, productId, newQuantity, inventory.getMinimumStock());
                return inventoryRepository.findByProductIdAndBranchId(productId, branchId).orElseThrow();
            }
        }
        throw new ResourceConflictException("CONFLICTO_CONCURRENCIA", "No se pudo actualizar el inventario tras varios intentos por alta concurrencia.");
    }

    private BigDecimal resolveConversionFactor(Long productId, Long unitOfMeasureId) {
        Product product = productRepository.findById(productId).orElseThrow();
        if (unitOfMeasureId.equals(product.getBaseUnitOfMeasureId())) {
            return BigDecimal.ONE;
        }
        ProductUnit productUnit = productUnitRepository.findByProductIdAndUnitOfMeasureId(productId, unitOfMeasureId)
                .orElseThrow(() -> new BusinessRuleViolationException("UNIDAD_NO_SOPORTADA", "El producto no admite esa unidad de medida."));
        return productUnit.getConversionFactorToBase();
    }

    private Inventory findOrCreateInventory(Long productId, Long branchId) {
        return inventoryRepository.findByProductIdAndBranchId(productId, branchId)
                .orElseGet(() -> {
                    BigDecimal minimumStock = productRepository.findById(productId)
                            .map(Product::getMinimumStock)
                            .orElse(BigDecimal.ZERO);
                    try {
                        return inventoryRepository.saveAndFlush(new Inventory(productId, branchId, minimumStock));
                    } catch (DataIntegrityViolationException raceOnFirstCreation) {
                        return inventoryRepository.findByProductIdAndBranchId(productId, branchId).orElseThrow();
                    }
                });
    }

    private PurchaseOrderItem findItemOrThrow(Long itemId, Long expectedOrderId) {
        PurchaseOrderItem item = purchaseOrderItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("LINEA_ORDEN_NO_ENCONTRADA", "Línea de orden de compra no encontrada."));
        if (!item.getPurchaseOrderId().equals(expectedOrderId)) {
            throw new ResourceNotFoundException("LINEA_ORDEN_NO_ENCONTRADA", "La línea no pertenece a esta orden de compra.");
        }
        return item;
    }
}
