package com.inventario.multisucursal.transfers;

import com.inventario.multisucursal.auth.AuthorizationService;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.BadRequestException;
import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.common.reports.ReportRangeValidator;
import com.inventario.multisucursal.common.web.PageResponse;
import com.inventario.multisucursal.events.DomainEvent;
import com.inventario.multisucursal.events.DomainEventPublisher;
import com.inventario.multisucursal.inventory.Inventory;
import com.inventario.multisucursal.inventory.InventoryMovement;
import com.inventario.multisucursal.inventory.InventoryMovementRepository;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.inventory.MovementDirection;
import com.inventario.multisucursal.inventory.MovementReason;
import com.inventario.multisucursal.inventory.StockAlertService;
import com.inventario.multisucursal.logistics.Route;
import com.inventario.multisucursal.logistics.RouteService;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ciclo completo de transferencia entre sucursales (flujos C–F de
 * docs/CRITICAL_FLOWS.md; RF-022 a RF-026; BR-005 a BR-009, BR-013, BR-014,
 * BR-018, BR-020).
 *
 * <p><b>Cómo se resuelven los riesgos del flujo</b> — cada uno tiene un
 * mecanismo concreto, no una comprobación en memoria:
 * <ul>
 *   <li><b>Despacho / recepción / aprobación duplicados:</b> cada transición
 *       es un {@code UPDATE ... WHERE status = <esperado>} atómico
 *       ({@link TransferRepository}); 0 filas ⇒ 409. Además, cada línea se
 *       protege por separado con {@code WHERE <columna> IS NULL}
 *       ({@link TransferItemRepository}), de modo que ni siquiera dos
 *       recepciones parciales de la misma línea pueden aplicarse dos veces.</li>
 *   <li><b>Stock consumido por una venta mientras la transferencia se
 *       prepara:</b> aprobar <i>no</i> reserva stock (decisión de diseño
 *       explícita, docs/CRITICAL_FLOWS.md flujo C2); el despacho revalida la
 *       disponibilidad real en ese instante (BR-013, escenario 3.2) y compite
 *       con las ventas por la misma fila de {@code Inventory} mediante el
 *       mismo bloqueo optimista con reintento.</li>
 *   <li><b>Cantidad recibida &gt; enviada:</b> validación explícita (BR-014)
 *       más {@code CHECK} en base de datos.</li>
 *   <li><b>Rollback parcial:</b> cada operación es una única transacción; si
 *       una línea falla, ninguna queda aplicada.</li>
 *   <li><b>Acciones desde una sucursal no autorizada:</b> cada operación
 *       exige la sucursal que le corresponde — origen para aprobar/despachar,
 *       destino para solicitar/recibir (docs/API_DESIGN.md, sección 6).</li>
 * </ul>
 */
@Service
public class TransferService {

    private static final int MAX_RETRIES = 3;
    private static final int QUANTITY_SCALE = 6;
    /** BR-056: tope de filas de un reporte exportable — protege contra un rango sin acotar. */
    private static final int MAX_EXPORT_ROWS = 5000;

    private final TransferRepository transferRepository;
    private final TransferItemRepository transferItemRepository;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryMovementRepository movementRepository;
    private final RouteService routeService;
    private final DomainEventPublisher eventPublisher;
    private final AuthorizationService authorizationService;
    private final StockAlertService stockAlertService;

    public TransferService(
            TransferRepository transferRepository,
            TransferItemRepository transferItemRepository,
            BranchRepository branchRepository,
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            InventoryMovementRepository movementRepository,
            RouteService routeService,
            DomainEventPublisher eventPublisher,
            AuthorizationService authorizationService,
            StockAlertService stockAlertService) {
        this.transferRepository = transferRepository;
        this.transferItemRepository = transferItemRepository;
        this.branchRepository = branchRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.movementRepository = movementRepository;
        this.routeService = routeService;
        this.eventPublisher = eventPublisher;
        this.authorizationService = authorizationService;
        this.stockAlertService = stockAlertService;
    }

    // ---- C1: solicitud ----

    @Transactional
    public TransferResponse request(CreateTransferRequest request, Long requestedByUserId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUERIDO", "El encabezado Idempotency-Key es obligatorio.");
        }
        var existing = transferRepository.findByClientReferenceId(idempotencyKey);
        if (existing.isPresent()) {
            return buildResponse(existing.get().getId());
        }

        // La solicitud la origina la sucursal destino (docs/CRITICAL_FLOWS.md, flujo C1).
        authorizationService.requireBranchAccess(request.destinationBranchId());

        if (request.originBranchId().equals(request.destinationBranchId())) {
            throw new BusinessRuleViolationException("ORIGEN_IGUAL_DESTINO", "El origen y el destino deben ser sucursales distintas.");
        }
        requireActiveBranch(request.originBranchId(), "origen");
        requireActiveBranch(request.destinationBranchId(), "destino");

        Transfer transfer = transferRepository.save(new Transfer(
                generateTransferNumber(), request.originBranchId(), request.destinationBranchId(),
                request.urgency(), requestedByUserId, idempotencyKey,
                resolveRouteId(request.originBranchId(), request.destinationBranchId())));

        Set<Long> seenProducts = new HashSet<>();
        for (CreateTransferItemRequest itemRequest : request.items()) {
            if (!seenProducts.add(itemRequest.productId())) {
                throw new BusinessRuleViolationException(
                        "PRODUCTO_DUPLICADO_EN_TRANSFERENCIA", "Un producto no puede repetirse en la misma transferencia.");
            }
            if (itemRequest.quantityRequested().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessRuleViolationException("CANTIDAD_INVALIDA", "La cantidad solicitada debe ser mayor que cero.");
            }
            Product product = requireActiveProduct(itemRequest.productId());
            transferItemRepository.save(new TransferItem(
                    transfer.getId(), product.getId(), product.getBaseUnitOfMeasureId(), itemRequest.quantityRequested()));
        }

        return buildResponse(transfer.getId());
    }

    // ---- C2: aprobación / rechazo ----

    @Transactional
    public TransferResponse approve(Long transferId, ApproveTransferRequest request, Long approvedByUserId) {
        Transfer transfer = findOrThrow(transferId);
        authorizationService.requireBranchAccess(transfer.getOriginBranchId());
        requireStatus(transfer, TransferStatus.REQUESTED);

        Map<Long, BigDecimal> approvedByItem = indexRequest(
                request.items(), ApproveTransferItemRequest::transferItemId, ApproveTransferItemRequest::quantityApproved);
        List<TransferItem> items = requireAllItemsPresent(transferId, approvedByItem.keySet(), "APROBACION_INCOMPLETA",
                "La aprobación debe incluir todas las líneas de la transferencia.");

        for (TransferItem item : items) {
            BigDecimal quantityApproved = approvedByItem.get(item.getId());
            if (quantityApproved.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessRuleViolationException("CANTIDAD_INVALIDA", "La cantidad aprobada debe ser mayor que cero.");
            }
            if (quantityApproved.compareTo(item.getQuantityRequested()) > 0) {
                throw new BusinessRuleViolationException(
                        "CANTIDAD_APROBADA_EXCEDE_SOLICITADO",
                        "La cantidad aprobada (" + quantityApproved + ") excede la solicitada (" + item.getQuantityRequested() + ").");
            }
            // BR-005: disponibilidad al aprobar. Es una lectura simple, sin bloqueo:
            // aprobar no reserva stock, y el despacho vuelve a validar (BR-013).
            BigDecimal available = availableStock(item.getProductId(), transfer.getOriginBranchId());
            if (available.compareTo(quantityApproved) < 0) {
                throw new BusinessRuleViolationException(
                        "STOCK_INSUFICIENTE_PARA_TRANSFERENCIA",
                        "El stock disponible en la sucursal origen (" + available + ") es menor a la cantidad a aprobar (" + quantityApproved + ").");
            }
        }

        if (transferRepository.markApproved(transferId, approvedByUserId, Instant.now()) == 0) {
            throw new ResourceConflictException("TRANSICION_INVALIDA", "La transferencia ya no está en estado REQUESTED.");
        }
        for (TransferItem item : items) {
            if (transferItemRepository.markApproved(item.getId(), approvedByItem.get(item.getId())) == 0) {
                throw new ResourceConflictException("TRANSICION_INVALIDA", "La línea ya tenía una cantidad aprobada.");
            }
        }
        publishStatusChanged(transfer);
        return buildResponse(transferId);
    }

    @Transactional
    public TransferResponse reject(Long transferId, Long rejectedByUserId) {
        Transfer transfer = findOrThrow(transferId);
        authorizationService.requireBranchAccess(transfer.getOriginBranchId());
        if (transferRepository.markRejected(transferId, rejectedByUserId, Instant.now()) == 0) {
            throw new ResourceConflictException("TRANSICION_INVALIDA", "La transferencia ya no está en estado REQUESTED.");
        }
        publishStatusChanged(transfer);
        return buildResponse(transferId);
    }

    // ---- D: despacho ----

    @Transactional
    public TransferResponse dispatch(Long transferId, DispatchTransferRequest request, Long dispatchedByUserId) {
        Transfer transfer = findOrThrow(transferId);
        authorizationService.requireBranchAccess(transfer.getOriginBranchId());
        requireStatus(transfer, TransferStatus.APPROVED);

        Map<Long, BigDecimal> shippedByItem = indexRequest(
                request.items(), DispatchTransferItemRequest::transferItemId, DispatchTransferItemRequest::quantityShipped);
        // Una transferencia tiene un único tramo de envío: el despacho no es
        // parcial por línea (docs/API_DESIGN.md, sección 7.9).
        List<TransferItem> items = requireAllItemsPresent(transferId, shippedByItem.keySet(), "DESPACHO_INCOMPLETO",
                "El despacho debe incluir todas las líneas de la transferencia.");

        for (TransferItem item : items) {
            BigDecimal quantityShipped = shippedByItem.get(item.getId());
            if (quantityShipped.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessRuleViolationException("CANTIDAD_INVALIDA", "La cantidad despachada debe ser mayor que cero.");
            }
            if (quantityShipped.compareTo(item.getQuantityApproved()) > 0) {
                throw new BusinessRuleViolationException(
                        "CANTIDAD_DESPACHO_EXCEDE_APROBADO",
                        "La cantidad a despachar (" + quantityShipped + ") excede la aprobada (" + item.getQuantityApproved() + ").");
            }
        }

        if (transferRepository.markDispatched(transferId, request.carrierName(), request.estimatedArrivalDate(), Instant.now()) == 0) {
            throw new ResourceConflictException("TRANSICION_INVALIDA", "La transferencia ya no está en estado APPROVED.");
        }

        // Orden determinista por producto, no el orden en que
        // findByTransferId devolvió las líneas: dos transacciones
        // concurrentes que tocan las mismas filas de Inventory en orden
        // opuesto pueden producir un deadlock real de base de datos, no solo
        // un reintento optimista (mismo hallazgo que SaleService.confirmSale,
        // ver SaleOppositeOrderConcurrencyTest / docs/TEST_STRATEGY.md).
        List<TransferItem> orderedItems = items.stream()
                .sorted(Comparator.comparing(TransferItem::getProductId))
                .toList();
        for (TransferItem item : orderedItems) {
            BigDecimal quantityShipped = shippedByItem.get(item.getId());
            // BR-013 / escenario 3.2: el stock pudo consumirse por una venta desde la
            // aprobación, así que se revalida y descuenta aquí, compitiendo con ventas
            // concurrentes por la misma fila de Inventory.
            applyWithdrawal(item.getProductId(), transfer.getOriginBranchId(), quantityShipped);
            if (transferItemRepository.markShipped(item.getId(), quantityShipped) == 0) {
                throw new ResourceConflictException("TRANSICION_INVALIDA", "La línea ya había sido despachada.");
            }
            movementRepository.save(InventoryMovement.forTransfer(
                    item.getProductId(), transfer.getOriginBranchId(), MovementDirection.RETIRO,
                    MovementReason.TRANSFERENCIA_SALIDA, quantityShipped, item.getUnitOfMeasureId(),
                    dispatchedByUserId, null, item.getId()));
            eventPublisher.publish(DomainEvent.inventoryUpdated(transfer.getOriginBranchId(), item.getProductId()));
        }

        publishStatusChanged(transfer);
        return buildResponse(transferId);
    }

    // ---- E / F1: recepción completa o parcial ----

    @Transactional
    public TransferResponse receive(Long transferId, ReceiveTransferRequest request, Long receivedByUserId) {
        Transfer transfer = findOrThrow(transferId);
        authorizationService.requireBranchAccess(transfer.getDestinationBranchId());
        requireStatus(transfer, TransferStatus.IN_TRANSIT);

        Map<Long, BigDecimal> receivedByItem = indexRequest(
                request.items(), ReceiveTransferItemRequest::transferItemId, ReceiveTransferItemRequest::quantityReceived);

        // Orden determinista por producto, no el orden de llegada del
        // payload: mismo motivo que dispatch(...) y SaleService.confirmSale
        // — se resuelven las líneas de antemano (sin N+1 dentro del bucle,
        // reutilizando el mismo findByTransferId que ya hace requireAllItemsPresent
        // en las demás transiciones) para poder ordenarlas por productId antes
        // de tocar Inventory (ver SaleOppositeOrderConcurrencyTest,
        // docs/TEST_STRATEGY.md).
        Map<Long, TransferItem> itemsById = transferItemRepository.findByTransferId(transferId).stream()
                .collect(Collectors.toMap(TransferItem::getId, item -> item));
        List<Long> orderedItemIds = receivedByItem.keySet().stream()
                .sorted(Comparator.comparing(id -> {
                    TransferItem item = itemsById.get(id);
                    return item != null ? item.getProductId() : Long.MAX_VALUE;
                }))
                .toList();

        for (Long itemId : orderedItemIds) {
            TransferItem item = findItemOrThrow(itemId, transferId);
            BigDecimal quantityReceived = receivedByItem.get(itemId);

            if (quantityReceived.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessRuleViolationException("CANTIDAD_INVALIDA", "La cantidad recibida no puede ser negativa.");
            }
            if (quantityReceived.compareTo(item.getQuantityShipped()) > 0) {
                throw new BusinessRuleViolationException(
                        "RECEPCION_EXCEDE_ENVIADO",
                        "La cantidad recibida (" + quantityReceived + ") excede la despachada (" + item.getQuantityShipped() + ").");
            }

            BigDecimal missing = item.getQuantityShipped().subtract(quantityReceived);
            // quantityMissing nulo cuando la recepción fue completa (docs/API_DESIGN.md, ejemplo 9.6).
            BigDecimal storedMissing = missing.compareTo(BigDecimal.ZERO) > 0 ? missing : null;
            if (transferItemRepository.markReceived(item.getId(), quantityReceived, storedMissing) == 0) {
                throw new ResourceConflictException("RECEPCION_YA_REGISTRADA", "Esta línea ya tenía una recepción registrada.");
            }

            // Solo lo efectivamente recibido entra al inventario destino (flujo F1). Una
            // línea recibida en 0 no genera movimiento: un InventoryMovement de cantidad
            // cero violaría CHECK (quantity > 0) y no representa ningún hecho de stock.
            if (quantityReceived.compareTo(BigDecimal.ZERO) > 0) {
                applyEntry(item.getProductId(), transfer.getDestinationBranchId(), quantityReceived);
                movementRepository.save(InventoryMovement.forTransfer(
                        item.getProductId(), transfer.getDestinationBranchId(), MovementDirection.INGRESO,
                        MovementReason.TRANSFERENCIA_ENTRADA, quantityReceived, item.getUnitOfMeasureId(),
                        receivedByUserId, null, item.getId()));
                eventPublisher.publish(DomainEvent.inventoryUpdated(transfer.getDestinationBranchId(), item.getProductId()));
            }

            if (storedMissing != null) {
                // Faltante abierto: el evento avisa a quien deba decidir el tratamiento
                // (flujo F1). No reutiliza StockAlert, que es de stock mínimo (BR-008).
                eventPublisher.publish(DomainEvent.transferDiscrepancyOpened(
                        transferId, transfer.getOriginBranchId(), transfer.getDestinationBranchId()));
            }
        }

        // El estado solo avanza cuando todas las líneas quedaron atendidas — la
        // recepción admite subconjuntos (docs/API_DESIGN.md, sección 7.9).
        List<TransferItem> allItems = transferItemRepository.findByTransferId(transferId);
        boolean allReceived = allItems.stream().allMatch(item -> item.getQuantityReceived() != null);
        if (allReceived) {
            boolean anyShortage = allItems.stream()
                    .anyMatch(item -> item.getQuantityMissing() != null && item.getQuantityMissing().compareTo(BigDecimal.ZERO) > 0);
            TransferStatus newStatus = anyShortage ? TransferStatus.RECEIVED_PARTIAL : TransferStatus.RECEIVED_COMPLETE;
            if (transferRepository.markReceived(transferId, newStatus, Instant.now()) == 0) {
                throw new ResourceConflictException("TRANSICION_INVALIDA", "La transferencia ya no está en tránsito.");
            }
            publishStatusChanged(transfer);
        }

        return buildResponse(transferId);
    }

    // ---- F2: tratamiento del faltante y cierre ----

    @Transactional
    public DiscrepancyTreatmentResponse applyDiscrepancyTreatment(
            Long transferId, Long transferItemId, ApplyDiscrepancyTreatmentRequest request, Long treatedByUserId) {
        Transfer transfer = findOrThrow(transferId);
        authorizationService.requireAnyBranchAccess(transfer.getOriginBranchId(), transfer.getDestinationBranchId());

        TransferItem item = findItemOrThrow(transferItemId, transferId);
        if (item.getQuantityMissing() == null || item.getQuantityMissing().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResourceNotFoundException("LINEA_SIN_FALTANTE", "La línea no tiene un faltante registrado.");
        }

        // REENVIO crea la transferencia de reposición dentro de la misma transacción:
        // si su creación fallara, el tratamiento tampoco quedaría registrado (flujo F2).
        Long followUpTransferId = null;
        if (request.treatment() == DiscrepancyTreatment.REENVIO) {
            Transfer followUp = transferRepository.save(new Transfer(
                    generateTransferNumber(), transfer.getOriginBranchId(), transfer.getDestinationBranchId(),
                    transfer.isUrgency(), treatedByUserId, null,
                    resolveRouteId(transfer.getOriginBranchId(), transfer.getDestinationBranchId())));
            transferItemRepository.save(new TransferItem(
                    followUp.getId(), item.getProductId(), item.getUnitOfMeasureId(), item.getQuantityMissing()));
            followUpTransferId = followUp.getId();
        }

        if (transferItemRepository.markTreated(transferItemId, request.treatment(), treatedByUserId, Instant.now(), followUpTransferId, request.notes()) == 0) {
            throw new ResourceConflictException("FALTANTE_YA_TRATADO", "El faltante de esta línea ya tenía un tratamiento definido.");
        }

        // Cierre solo cuando ya no queda ningún faltante sin tratar (escenario 3.5).
        boolean anyUntreated = transferItemRepository.findByTransferId(transferId).stream()
                .anyMatch(TransferItem::hasUntreatedShortage);
        TransferStatus finalStatus = transfer.getStatus();
        if (!anyUntreated && transferRepository.markClosed(transferId) == 1) {
            finalStatus = TransferStatus.CLOSED;
            publishStatusChanged(transfer);
        }

        return new DiscrepancyTreatmentResponse(
                String.valueOf(transferItemId),
                request.treatment(),
                request.notes(),
                followUpTransferId != null ? String.valueOf(followUpTransferId) : null,
                finalStatus);
    }

    // ---- Consulta ----

    public TransferResponse getById(Long transferId) {
        Transfer transfer = findOrThrow(transferId);
        authorizationService.requireAnyBranchAccess(transfer.getOriginBranchId(), transfer.getDestinationBranchId());
        return buildResponse(transferId);
    }

    public PageResponse<TransferResponse> list(Long branchId, String role, TransferStatus status, Pageable pageable) {
        Long scope = authorizationService.currentBranchScopeOrNull();
        Long effectiveBranchId = scope != null ? scope : branchId;
        return PageResponse.from(transferRepository.search(effectiveBranchId, role, status, pageable)
                .map(transfer -> TransferResponse.from(transfer, transferItemRepository.findByTransferId(transfer.getId()))));
    }

    /**
     * BR-056: base del reporte exportable de transferencias. {@code dateFrom}/
     * {@code dateTo} son obligatorios y el resultado está acotado a
     * {@value #MAX_EXPORT_ROWS} filas, a diferencia de {@link #list}.
     */
    public List<TransferResponse> listForExport(Long branchId, TransferStatus status, Instant dateFrom, Instant dateTo) {
        ReportRangeValidator.requireValidRange(dateFrom, dateTo);
        Long effectiveBranchId = authorizationService.resolveBranchFilter(branchId);

        Pageable capped = PageRequest.of(0, MAX_EXPORT_ROWS + 1);
        Page<Transfer> page = transferRepository.searchForReport(effectiveBranchId, status, dateFrom, dateTo, capped);
        ReportRangeValidator.requireWithinRowLimit(page.getTotalElements(), MAX_EXPORT_ROWS, "transferencias");

        return page.getContent().stream()
                .map(transfer -> TransferResponse.from(transfer, transferItemRepository.findByTransferId(transfer.getId())))
                .toList();
    }

    /**
     * Transferencias activas de una sucursal con su impacto en inventario
     * (BR-041, dashboard RF-033). Sin autorización propia — mismo criterio que
     * {@link #findDispatchedForCompliance}: quien llama ya resolvió el
     * alcance de sucursal.
     */
    public List<ActiveTransferImpact> findActiveForDashboard(Long branchId) {
        List<Transfer> transfers = transferRepository.findActive(branchId);
        if (transfers.isEmpty()) {
            return List.of();
        }
        List<Long> transferIds = transfers.stream().map(Transfer::getId).toList();
        Map<Long, List<TransferItem>> itemsByTransfer = transferItemRepository.findByTransferIdIn(transferIds).stream()
                .collect(Collectors.groupingBy(TransferItem::getTransferId));

        List<ActiveTransferImpact> impacts = new ArrayList<>();
        for (Transfer transfer : transfers) {
            BigDecimal unitsInTransit = BigDecimal.ZERO;
            BigDecimal unitsPendingDispatch = BigDecimal.ZERO;
            for (TransferItem item : itemsByTransfer.getOrDefault(transfer.getId(), List.of())) {
                if (item.getQuantityShipped() == null) {
                    // Todavía no se despachó: el efecto es enteramente proyectado.
                    BigDecimal committed = item.getQuantityApproved() != null ? item.getQuantityApproved() : item.getQuantityRequested();
                    unitsPendingDispatch = unitsPendingDispatch.add(committed);
                } else {
                    // Ya se despachó —una sola vez, BR-034— así que lo no despachado
                    // de esta línea no queda "pendiente": el despacho de una
                    // transferencia es un único evento por línea, no admite un
                    // segundo envío posterior para completar la diferencia.
                    BigDecimal received = item.getQuantityReceived() != null ? item.getQuantityReceived() : BigDecimal.ZERO;
                    unitsInTransit = unitsInTransit.add(item.getQuantityShipped().subtract(received));
                }
            }
            impacts.add(new ActiveTransferImpact(
                    transfer.getId(), transfer.getTransferNumber(), transfer.getStatus(),
                    transfer.getOriginBranchId(), transfer.getDestinationBranchId(), transfer.isUrgency(),
                    unitsInTransit, unitsPendingDispatch));
        }
        return impacts;
    }

    // ---- helpers ----

    /**
     * BR-022 aplicado al retiro por despacho: no crea la fila de
     * {@code Inventory} si no existe — despachar desde una sucursal que nunca
     * tuvo el producto es, por definición, stock insuficiente.
     */
    private void applyWithdrawal(Long productId, Long branchId, BigDecimal quantity) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Inventory inventory = inventoryRepository.findByProductIdAndBranchId(productId, branchId).orElse(null);
            BigDecimal available = inventory != null ? inventory.getQuantityOnHand() : BigDecimal.ZERO;
            if (available.compareTo(quantity) < 0) {
                throw new BusinessRuleViolationException(
                        "STOCK_INSUFICIENTE",
                        "El stock disponible (" + available + ") es menor a la cantidad a despachar (" + quantity + ").");
            }
            BigDecimal newQuantity = available.subtract(quantity);
            if (inventoryRepository.applyQuantity(inventory.getId(), inventory.getVersion(), newQuantity, Instant.now()) == 1) {
                stockAlertService.evaluate(inventory.getId(), branchId, productId, newQuantity, inventory.getMinimumStock());
                return;
            }
        }
        throw new ResourceConflictException(
                "CONFLICTO_CONCURRENCIA", "No se pudo despachar tras varios intentos por alta concurrencia sobre el mismo stock.");
    }

    /** La sucursal destino puede no haber tenido nunca el producto: aquí sí se crea la fila. */
    private void applyEntry(Long productId, Long branchId, BigDecimal quantity) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Inventory inventory = findOrCreateInventory(productId, branchId);
            BigDecimal newQuantity = inventory.getQuantityOnHand().add(quantity).setScale(QUANTITY_SCALE, java.math.RoundingMode.HALF_UP);
            if (inventoryRepository.applyQuantity(inventory.getId(), inventory.getVersion(), newQuantity, Instant.now()) == 1) {
                stockAlertService.evaluate(inventory.getId(), branchId, productId, newQuantity, inventory.getMinimumStock());
                return;
            }
        }
        throw new ResourceConflictException(
                "CONFLICTO_CONCURRENCIA", "No se pudo registrar la recepción tras varios intentos por alta concurrencia.");
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

    private BigDecimal availableStock(Long productId, Long branchId) {
        return inventoryRepository.findByProductIdAndBranchId(productId, branchId)
                .map(Inventory::getQuantityOnHand)
                .orElse(BigDecimal.ZERO);
    }

    private <T> Map<Long, BigDecimal> indexRequest(
            List<T> requestItems, java.util.function.Function<T, Long> idExtractor, java.util.function.Function<T, BigDecimal> valueExtractor) {
        Map<Long, BigDecimal> indexed = new LinkedHashMap<>();
        for (T requestItem : requestItems) {
            if (indexed.put(idExtractor.apply(requestItem), valueExtractor.apply(requestItem)) != null) {
                throw new BusinessRuleViolationException(
                        "LINEA_DUPLICADA_EN_SOLICITUD", "Una misma línea no puede aparecer dos veces en la solicitud.");
            }
        }
        return indexed;
    }

    private List<TransferItem> requireAllItemsPresent(Long transferId, Set<Long> providedItemIds, String errorCode, String message) {
        List<TransferItem> items = transferItemRepository.findByTransferId(transferId);
        for (Long providedId : providedItemIds) {
            if (items.stream().noneMatch(item -> item.getId().equals(providedId))) {
                throw new ResourceNotFoundException("LINEA_TRANSFERENCIA_NO_ENCONTRADA", "La línea no pertenece a esta transferencia.");
            }
        }
        if (items.size() != providedItemIds.size()) {
            throw new BusinessRuleViolationException(errorCode, message);
        }
        return items;
    }

    private void requireStatus(Transfer transfer, TransferStatus expected) {
        if (transfer.getStatus() != expected) {
            throw new ResourceConflictException(
                    "TRANSICION_INVALIDA",
                    "La transferencia está en estado " + transfer.getStatus() + "; esta operación requiere " + expected + ".");
        }
    }

    private void requireActiveBranch(Long branchId, String label) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("SUCURSAL_NO_ENCONTRADA", "Sucursal " + label + " no encontrada."));
        if (!branch.isActive()) {
            throw new ResourceConflictException("SUCURSAL_INACTIVA", "La sucursal " + label + " está inactiva.");
        }
    }

    private Product requireActiveProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado."));
        if (!product.isActive()) {
            throw new ResourceConflictException("PRODUCTO_INACTIVO", "El producto está inactivo.");
        }
        return product;
    }

    private Transfer findOrThrow(Long transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("TRANSFERENCIA_NO_ENCONTRADA", "Transferencia no encontrada."));
    }

    private TransferItem findItemOrThrow(Long transferItemId, Long expectedTransferId) {
        TransferItem item = transferItemRepository.findById(transferItemId)
                .orElseThrow(() -> new ResourceNotFoundException("LINEA_TRANSFERENCIA_NO_ENCONTRADA", "Línea de transferencia no encontrada."));
        if (!item.getTransferId().equals(expectedTransferId)) {
            throw new ResourceNotFoundException("LINEA_TRANSFERENCIA_NO_ENCONTRADA", "La línea no pertenece a esta transferencia.");
        }
        return item;
    }

    /**
     * Relee siempre desde la base de datos para construir la respuesta: los
     * {@code UPDATE} atómicos de arriba llevan {@code clearAutomatically},
     * así que cualquier copia en memoria quedó obsoleta. Devolver el estado
     * releído (y no el que el servicio "cree" haber dejado) es justamente lo
     * que habría delatado antes el bug de entidad {@code detached} que este
     * proyecto ya sufrió en compras.
     */
    private TransferResponse buildResponse(Long transferId) {
        Transfer transfer = findOrThrow(transferId);
        return TransferResponse.from(transfer, transferItemRepository.findByTransferId(transferId));
    }

    /**
     * Señal de que la transferencia cambió de estado (RF-029). Se publica
     * dentro de la transacción pero solo se emite tras el commit — si la
     * operación termina en rollback, nadie recibe aviso de una transición que
     * no ocurrió (ADR-007).
     */
    private void publishStatusChanged(Transfer transfer) {
        eventPublisher.publish(DomainEvent.transferStatusChanged(
                transfer.getId(), transfer.getOriginBranchId(), transfer.getDestinationBranchId()));
    }

    /**
     * La ruta se deduce del par de sucursales, nunca llega en el payload
     * (RF-028; BR-036). Si ese par todavía no está clasificado, la
     * transferencia queda sin ruta — no es un error.
     */
    private Long resolveRouteId(Long originBranchId, Long destinationBranchId) {
        return routeService.findByBranchPair(originBranchId, destinationBranchId).map(Route::getId).orElse(null);
    }

    /**
     * Lectura para el reporte de cumplimiento logístico (RF-030), que vive en
     * el módulo `reports` — hoja del grafo de dependencias
     * (docs/ARCHITECTURE.md, sección 4), que lee de otros módulos a través de
     * su capa de servicio y nunca de sus repositorios.
     *
     * <p>No aplica autorización por sucursal: la aplica el propio servicio de
     * reportes, que es quien conoce el alcance pedido y las reglas de
     * docs/API_DESIGN.md sección 6 para ese endpoint.
     */
    public List<Transfer> findDispatchedForCompliance(
            Long branchId, Long originBranchId, Long destinationBranchId, Instant dispatchedFrom, Instant dispatchedTo) {
        return transferRepository.findDispatchedForCompliance(branchId, originBranchId, destinationBranchId, dispatchedFrom, dispatchedTo);
    }

    private String generateTransferNumber() {
        return "TR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
