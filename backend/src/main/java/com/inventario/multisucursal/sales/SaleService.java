package com.inventario.multisucursal.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventario.multisucursal.auth.AuthenticatedUser;
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
import com.inventario.multisucursal.products.Price;
import com.inventario.multisucursal.products.PriceList;
import com.inventario.multisucursal.products.PriceListRepository;
import com.inventario.multisucursal.products.PriceRepository;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.ProductUnit;
import com.inventario.multisucursal.products.ProductUnitRepository;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;

/**
 * Registro y confirmación de venta (flujo A, docs/CRITICAL_FLOWS.md; RF-017
 * a RF-021; BR-002, BR-012, BR-018, BR-019). Una venta se crea directamente
 * en {@code CONFIRMED} dentro de una única transacción: valida → decrementa
 * inventario (bloqueo optimista con reintento, igual patrón que
 * {@code InventoryMovementService}/{@code PurchaseReceiptService}) →
 * registra {@code InventoryMovement} por línea → solo entonces persiste los
 * totales — si cualquier línea falla, toda la venta se revierte (BR-021,
 * escenario 3.4 de docs/CRITICAL_FLOWS.md).
 */
@Service
public class SaleService {

    private static final int MAX_RETRIES = 3;
    private static final int QUANTITY_SCALE = 6;
    private static final int MONEY_SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final Instant MIN_SALE_DATE = Instant.parse("1900-01-01T00:00:00Z");
    private static final Instant MAX_SALE_DATE = Instant.parse("9999-12-31T23:59:59Z");
    /** BR-056: tope de filas de un reporte exportable — protege contra un rango sin acotar. */
    private static final int MAX_EXPORT_ROWS = 5000;

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final ProductUnitRepository productUnitRepository;
    private final PriceListRepository priceListRepository;
    private final PriceRepository priceRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryMovementRepository movementRepository;
    private final UserRepository userRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuthorizationService authorizationService;
    private final StockAlertService stockAlertService;

    public SaleService(
            SaleRepository saleRepository,
            SaleItemRepository saleItemRepository,
            BranchRepository branchRepository,
            ProductRepository productRepository,
            ProductUnitRepository productUnitRepository,
            PriceListRepository priceListRepository,
            PriceRepository priceRepository,
            InventoryRepository inventoryRepository,
            InventoryMovementRepository movementRepository,
            UserRepository userRepository,
            DomainEventPublisher eventPublisher,
            AuthorizationService authorizationService,
            StockAlertService stockAlertService) {
        this.saleRepository = saleRepository;
        this.saleItemRepository = saleItemRepository;
        this.branchRepository = branchRepository;
        this.productRepository = productRepository;
        this.productUnitRepository = productUnitRepository;
        this.priceListRepository = priceListRepository;
        this.priceRepository = priceRepository;
        this.inventoryRepository = inventoryRepository;
        this.movementRepository = movementRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.authorizationService = authorizationService;
        this.stockAlertService = stockAlertService;
    }

    @Transactional
    public SaleResponse confirmSale(CreateSaleRequest request, Long soldByUserId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUERIDO", "El encabezado Idempotency-Key es obligatorio.");
        }

        var existingSale = saleRepository.findByClientReferenceId(idempotencyKey);
        if (existingSale.isPresent()) {
            Sale sale = existingSale.get();
            return SaleResponse.from(sale, saleItemRepository.findBySaleId(sale.getId()), resolveUserName(sale.getSoldByUserId()));
        }

        authorizationService.requireBranchAccess(request.branchId());

        Branch branch = branchRepository.findById(request.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("SUCURSAL_NO_ENCONTRADA", "Sucursal no encontrada."));
        if (!branch.isActive()) {
            throw new ResourceConflictException("SUCURSAL_INACTIVA", "La sucursal está inactiva.");
        }

        PriceList priceList = resolvePriceList(request.priceListId(), branch.getId());

        Sale sale = saleRepository.save(new Sale(generateSaleNumber(), branch.getId(), soldByUserId, priceList.getId(), idempotencyKey));

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        List<SaleItem> items = new ArrayList<>();

        // Orden determinista por producto, no el orden de llegada del payload:
        // dos ventas concurrentes con los mismos productos en orden distinto
        // pueden, si no, bloquear la fila de Inventory de cada una en orden
        // opuesto y producir un deadlock real de base de datos (confirmado con
        // SaleOppositeOrderConcurrencyTest) — el UPDATE de applyWithdrawal
        // retiene el lock de fila hasta el commit aunque el bloqueo sea
        // optimista a nivel de aplicación (BR-022). Ordenar por productId
        // garantiza que cualquier transacción que toque las mismas dos filas
        // las bloquee siempre en la misma secuencia.
        List<CreateSaleItemRequest> orderedItems = request.items().stream()
                .sorted(Comparator.comparing(CreateSaleItemRequest::productId))
                .toList();
        for (CreateSaleItemRequest itemRequest : orderedItems) {
            if (itemRequest.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessRuleViolationException("CANTIDAD_INVALIDA", "La cantidad debe ser mayor que cero.");
            }

            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado."));
            if (!product.isActive()) {
                throw new ResourceConflictException("PRODUCTO_INACTIVO", "El producto está inactivo.");
            }

            Long unitOfMeasureId = itemRequest.unitOfMeasureId() != null ? itemRequest.unitOfMeasureId() : product.getBaseUnitOfMeasureId();
            BigDecimal conversionFactor = resolveConversionFactor(product, unitOfMeasureId);
            BigDecimal quantityBase = itemRequest.quantity().multiply(conversionFactor).setScale(QUANTITY_SCALE, ROUNDING);

            BigDecimal listUnitPrice = priceRepository.findByPriceListIdAndProductIdAndValidToIsNull(priceList.getId(), product.getId())
                    .map(Price::getUnitPrice)
                    .orElseThrow(() -> new BusinessRuleViolationException(
                            "PRECIO_NO_ENCONTRADO", "No hay un precio vigente para el producto en la lista de precios aplicada."));
            // BR-019: el precio de lista está fijado en la unidad base del producto
            // (RF-011); si la línea usa una unidad alternativa, se escala por el
            // mismo factor de conversión ya aplicado a la cantidad — igual idea que
            // PurchaseReceiptService.applyInventoryReceipt ya usa para el costo
            // promedio ponderado, aquí sobre el precio de venta.
            BigDecimal unitPrice = conversionFactor.compareTo(BigDecimal.ONE) == 0
                    ? listUnitPrice
                    : listUnitPrice.multiply(conversionFactor).setScale(MONEY_SCALE, ROUNDING);

            BigDecimal discount = itemRequest.discountPercentage() != null ? itemRequest.discountPercentage() : BigDecimal.ZERO;
            BigDecimal lineSubtotal = unitPrice.multiply(itemRequest.quantity());
            BigDecimal discountAmount = lineSubtotal.multiply(discount).divide(BigDecimal.valueOf(100), MONEY_SCALE, ROUNDING);
            BigDecimal lineTotal = lineSubtotal.subtract(discountAmount).setScale(MONEY_SCALE, ROUNDING);

            applyWithdrawal(product.getId(), branch.getId(), quantityBase);

            SaleItem savedItem = saleItemRepository.save(new SaleItem(
                    sale.getId(), product.getId(), unitOfMeasureId, itemRequest.quantity(), unitPrice, discount, lineTotal));

            movementRepository.save(new InventoryMovement(
                    product.getId(), branch.getId(), MovementDirection.RETIRO, MovementReason.VENTA,
                    itemRequest.quantity(), unitOfMeasureId, soldByUserId, null, savedItem.getId()));
            eventPublisher.publish(DomainEvent.inventoryUpdated(branch.getId(), product.getId()));

            items.add(savedItem);
            subtotal = subtotal.add(lineSubtotal);
            discountTotal = discountTotal.add(discountAmount);
        }

        BigDecimal total = subtotal.subtract(discountTotal).setScale(MONEY_SCALE, ROUNDING);
        sale.updateTotals(subtotal.setScale(MONEY_SCALE, ROUNDING), discountTotal.setScale(MONEY_SCALE, ROUNDING), total);
        // `sale` puede haber quedado detached: applyWithdrawal usa un UPDATE
        // atómico con clearAutomatically = true, que vacía el contexto de
        // persistencia en cada intento — el dirty-checking implícito no
        // alcanza a persistir este último cambio (mismo defecto ya corregido
        // en PurchaseReceiptService); se guarda explícitamente.
        sale = saleRepository.save(sale);

        // Notifica al Data Lake que la venta está completa con todos sus datos.
        // Se publica después de persistir los totales, no antes — el Data Lake
        // leerá el documento ya completo (mismo criterio AFTER_COMMIT).
        eventPublisher.publish(DomainEvent.saleConfirmed(sale.getId(), sale.getBranchId()));

        return SaleResponse.from(sale, items, resolveUserName(sale.getSoldByUserId()));
    }

    public SaleResponse getById(Long id) {
        Sale sale = findOrThrow(id);
        authorizationService.requireBranchAccess(sale.getBranchId());
        return SaleResponse.from(sale, saleItemRepository.findBySaleId(sale.getId()), resolveUserName(sale.getSoldByUserId()));
    }

    public PageResponse<SaleResponse> list(Long branchId, SaleStatus status, Instant dateFrom, Instant dateTo, Pageable pageable) {
        Long effectiveBranchId = currentUser().role() == RoleCode.ADMIN ? branchId : currentUser().branchId();
        Instant effectiveFrom = dateFrom != null ? dateFrom : MIN_SALE_DATE;
        Instant effectiveTo = dateTo != null ? dateTo : MAX_SALE_DATE;
        Page<Sale> page = saleRepository.search(effectiveBranchId, status, effectiveFrom, effectiveTo, pageable);
        Map<Long, String> namesByUserId = resolveUserNames(page.getContent());
        return PageResponse.from(page.map(sale -> SaleResponse.from(
                sale, saleItemRepository.findBySaleId(sale.getId()), namesByUserId.get(sale.getSoldByUserId()))));
    }

    /**
     * BR-056: base del reporte exportable de ventas. {@code dateFrom}/
     * {@code dateTo} son obligatorios (a diferencia de {@link #list}, que
     * los completa con límites amplios por defecto) y el resultado está
     * acotado a {@value #MAX_EXPORT_ROWS} filas.
     */
    public List<SaleResponse> listForExport(Long branchId, SaleStatus status, Instant dateFrom, Instant dateTo) {
        ReportRangeValidator.requireValidRange(dateFrom, dateTo);
        Long effectiveBranchId = authorizationService.resolveBranchFilter(branchId);

        Pageable capped = PageRequest.of(0, MAX_EXPORT_ROWS + 1, Sort.by(Sort.Direction.DESC, "saleDate"));
        Page<Sale> page = saleRepository.search(effectiveBranchId, status, dateFrom, dateTo, capped);
        ReportRangeValidator.requireWithinRowLimit(page.getTotalElements(), MAX_EXPORT_ROWS, "ventas");

        Map<Long, String> namesByUserId = resolveUserNames(page.getContent());
        return page.getContent().stream()
                .map(sale -> SaleResponse.from(sale, saleItemRepository.findBySaleId(sale.getId()), namesByUserId.get(sale.getSoldByUserId())))
                .toList();
    }

    /** BR-054: {@code GET /users} es ADMIN-only, así que el nombre del responsable se resuelve aquí, no en el cliente. */
    private String resolveUserName(Long userId) {
        return userRepository.findById(userId).map(User::getName).orElse(null);
    }

    private Map<Long, String> resolveUserNames(List<Sale> sales) {
        if (sales.isEmpty()) {
            return Map.of();
        }
        List<Long> userIds = sales.stream().map(Sale::getSoldByUserId).distinct().toList();
        return userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getName));
    }

    /**
     * Totales de venta confirmada en un rango de fechas de una sucursal
     * (BR-039). Sin autorización propia: quien llama —{@code dashboard}— ya
     * resolvió el alcance de sucursal antes de pedir el dato, mismo criterio
     * que {@code TransferService.findDispatchedForCompliance} para
     * {@code reports}.
     */
    public SalesAggregate salesTotals(Long branchId, Instant from, Instant to) {
        return saleRepository.aggregateForRange(branchId, from, to);
    }

    /** Unidades vendidas por producto en una ventana, para una sucursal (BR-040). */
    public List<ProductDemand> demandByProduct(Long branchId, Instant from, Instant to) {
        return saleItemRepository.demandByProduct(branchId, from, to);
    }

    /**
     * BR-022 aplicado al retiro por venta (docs/CRITICAL_FLOWS.md, flujo A,
     * escenario 3.1): no crea la fila de {@code Inventory} si no existe —a
     * diferencia de una recepción de compra, una venta nunca es el primer
     * movimiento legítimo de un producto/sucursal, así que la ausencia de
     * stock ya es, por definición, stock insuficiente.
     */
    private void applyWithdrawal(Long productId, Long branchId, BigDecimal quantityBase) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Inventory inventory = inventoryRepository.findByProductIdAndBranchId(productId, branchId).orElse(null);
            BigDecimal available = inventory != null ? inventory.getQuantityOnHand() : BigDecimal.ZERO;
            if (available.compareTo(quantityBase) < 0) {
                throw new BusinessRuleViolationException(
                        "STOCK_INSUFICIENTE", "No hay stock suficiente. Disponible: " + available + ", solicitado: " + quantityBase + ".");
            }
            BigDecimal newQuantity = available.subtract(quantityBase);
            int updated = inventoryRepository.applyQuantity(inventory.getId(), inventory.getVersion(), newQuantity, Instant.now());
            if (updated == 1) {
                stockAlertService.evaluate(inventory.getId(), branchId, productId, newQuantity, inventory.getMinimumStock());
                return;
            }
        }
        throw new ResourceConflictException("CONFLICTO_CONCURRENCIA", "No se pudo confirmar la venta tras varios intentos por alta concurrencia sobre el mismo stock.");
    }

    private PriceList resolvePriceList(Long requestedPriceListId, Long branchId) {
        if (requestedPriceListId != null) {
            PriceList priceList = priceListRepository.findById(requestedPriceListId)
                    .orElseThrow(() -> new ResourceNotFoundException("LISTA_PRECIOS_NO_ENCONTRADA", "Lista de precios no encontrada."));
            if (!priceList.isActive()) {
                throw new ResourceConflictException("LISTA_PRECIOS_INACTIVA", "La lista de precios está inactiva.");
            }
            return priceList;
        }
        // [Decisión] sin priceListId explícito: se prioriza una lista activa propia de
        // la sucursal y, si no existe, la lista global activa (docs/DOMAIN_MODEL.md,
        // sección 2.13) — no está detallado en los documentos aprobados, es la
        // resolución más directa consistente con "branch_id nulo = lista global".
        return priceListRepository.findFirstByBranchIdAndActiveTrue(branchId)
                .or(priceListRepository::findFirstByBranchIdIsNullAndActiveTrue)
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "LISTA_PRECIOS_NO_ENCONTRADA", "No hay una lista de precios activa aplicable a esta sucursal."));
    }

    private BigDecimal resolveConversionFactor(Product product, Long unitOfMeasureId) {
        if (unitOfMeasureId.equals(product.getBaseUnitOfMeasureId())) {
            return BigDecimal.ONE;
        }
        ProductUnit productUnit = productUnitRepository.findByProductIdAndUnitOfMeasureId(product.getId(), unitOfMeasureId)
                .orElseThrow(() -> new BusinessRuleViolationException("UNIDAD_NO_SOPORTADA", "El producto no admite esa unidad de medida."));
        return productUnit.getConversionFactorToBase();
    }

    private String generateSaleNumber() {
        return "V-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Sale findOrThrow(Long id) {
        return saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("VENTA_NO_ENCONTRADA", "Venta no encontrada."));
    }

    private AuthenticatedUser currentUser() {
        return (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
