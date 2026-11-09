package com.inventario.multisucursal.inventory;

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
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.ProductUnit;
import com.inventario.multisucursal.products.ProductUnitRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Ajuste manual de inventario (flujo G, docs/CRITICAL_FLOWS.md; BR-023) y
 * consulta del ledger (RF-009). Es el único punto de entrada de esta fase
 * que escribe en {@link Inventory}/{@link InventoryMovement} — condición de
 * parada explícita: no genera movimientos con motivo {@code COMPRA},
 * {@code VENTA} ni {@code TRANSFERENCIA_*}, reservados para los módulos
 * `purchases`/`sales`/`transfers`, todavía no implementados.
 */
@Service
public class InventoryMovementService {

    private static final Set<MovementReason> ENTRY_REASONS = EnumSet.of(MovementReason.DEVOLUCION, MovementReason.AJUSTE_INGRESO);
    private static final Set<MovementReason> EXIT_REASONS = EnumSet.of(MovementReason.MERMA, MovementReason.AJUSTE_RETIRO);
    private static final int MAX_RETRIES = 3;
    private static final Instant MIN_OCCURRED_AT = Instant.parse("1900-01-01T00:00:00Z");
    private static final Instant MAX_OCCURRED_AT = Instant.parse("9999-12-31T23:59:59Z");
    /** BR-056: tope de filas de un reporte exportable — protege contra un rango sin acotar. */
    private static final int MAX_EXPORT_ROWS = 5000;

    private final InventoryRepository inventoryRepository;
    private final InventoryMovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final ProductUnitRepository productUnitRepository;
    private final BranchRepository branchRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuthorizationService authorizationService;
    private final StockAlertService stockAlertService;

    public InventoryMovementService(
            InventoryRepository inventoryRepository,
            InventoryMovementRepository movementRepository,
            ProductRepository productRepository,
            ProductUnitRepository productUnitRepository,
            BranchRepository branchRepository,
            DomainEventPublisher eventPublisher,
            AuthorizationService authorizationService,
            StockAlertService stockAlertService) {
        this.inventoryRepository = inventoryRepository;
        this.movementRepository = movementRepository;
        this.productRepository = productRepository;
        this.productUnitRepository = productUnitRepository;
        this.branchRepository = branchRepository;
        this.eventPublisher = eventPublisher;
        this.authorizationService = authorizationService;
        this.stockAlertService = stockAlertService;
    }

    @Transactional
    public InventoryMovementResponse createAdjustment(InventoryAdjustmentRequest request, Long responsibleUserId) {
        authorizationService.requireBranchAccess(request.branchId());

        if (request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleViolationException("CANTIDAD_INVALIDA", "La cantidad debe ser mayor que cero.");
        }
        if (request.notes() == null || request.notes().isBlank()) {
            throw new BadRequestException("NOTES_REQUERIDO", "El motivo (notes) es obligatorio en un ajuste manual.");
        }

        Branch branch = branchRepository.findById(request.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("SUCURSAL_NO_ENCONTRADA", "Sucursal no encontrada."));
        if (!branch.isActive()) {
            throw new ResourceConflictException("SUCURSAL_INACTIVA", "La sucursal está inactiva.");
        }

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado."));
        if (!product.isActive()) {
            throw new ResourceConflictException("PRODUCTO_INACTIVO", "El producto está inactivo.");
        }

        MovementReason reason = resolveReason(request.reason(), request.direction());

        Long unitOfMeasureId = request.unitOfMeasureId() != null ? request.unitOfMeasureId() : product.getBaseUnitOfMeasureId();
        BigDecimal conversionFactor = resolveConversionFactor(product, unitOfMeasureId);
        BigDecimal quantityInBaseUnit = request.quantity().multiply(conversionFactor).setScale(6, RoundingMode.HALF_UP);

        applyToInventory(product.getId(), branch.getId(), request.direction(), quantityInBaseUnit);

        InventoryMovement movement = movementRepository.save(new InventoryMovement(
                product.getId(),
                branch.getId(),
                request.direction(),
                reason,
                request.quantity(),
                unitOfMeasureId,
                responsibleUserId,
                request.notes()));

        eventPublisher.publish(DomainEvent.inventoryUpdated(branch.getId(), product.getId()));
        return InventoryMovementResponse.from(movement);
    }

    public PageResponse<InventoryMovementResponse> list(
            Long branchId, Long productId, MovementReason reason, Instant dateFrom, Instant dateTo, Pageable pageable) {
        Instant effectiveFrom = dateFrom != null ? dateFrom : MIN_OCCURRED_AT;
        Instant effectiveTo = dateTo != null ? dateTo : MAX_OCCURRED_AT;
        Page<InventoryMovement> page = movementRepository.search(branchId, productId, reason, effectiveFrom, effectiveTo, pageable);
        return PageResponse.from(page.map(InventoryMovementResponse::from));
    }

    /**
     * BR-056: base del reporte exportable de movimientos. A diferencia de
     * {@link #list}, {@code dateFrom}/{@code dateTo} son obligatorios (no se
     * completan con límites amplios por defecto) y el resultado está acotado
     * a {@value #MAX_EXPORT_ROWS} filas — una exportación materializa todo
     * en memoria de una vez, a diferencia de una página de la UI.
     */
    public List<InventoryMovementResponse> listForExport(
            Long branchId, Long productId, MovementReason reason, Instant dateFrom, Instant dateTo) {
        ReportRangeValidator.requireValidRange(dateFrom, dateTo);
        Long effectiveBranchId = authorizationService.resolveBranchFilter(branchId);

        Pageable capped = PageRequest.of(0, MAX_EXPORT_ROWS + 1, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<InventoryMovement> page = movementRepository.search(effectiveBranchId, productId, reason, dateFrom, dateTo, capped);
        ReportRangeValidator.requireWithinRowLimit(page.getTotalElements(), MAX_EXPORT_ROWS, "movimientos de inventario");
        return page.getContent().stream().map(InventoryMovementResponse::from).toList();
    }

    private MovementReason resolveReason(MovementReason requested, MovementDirection direction) {
        if (requested == null) {
            return direction == MovementDirection.INGRESO ? MovementReason.AJUSTE_INGRESO : MovementReason.AJUSTE_RETIRO;
        }
        Set<MovementReason> allowedForDirection = direction == MovementDirection.INGRESO ? ENTRY_REASONS : EXIT_REASONS;
        if (!allowedForDirection.contains(requested)) {
            throw new BusinessRuleViolationException(
                    "MOTIVO_INCOMPATIBLE_CON_DIRECCION",
                    "El motivo '" + requested + "' no es válido para un ajuste manual con dirección " + direction + ".");
        }
        return requested;
    }

    private BigDecimal resolveConversionFactor(Product product, Long unitOfMeasureId) {
        if (unitOfMeasureId.equals(product.getBaseUnitOfMeasureId())) {
            return BigDecimal.ONE;
        }
        ProductUnit productUnit = productUnitRepository.findByProductIdAndUnitOfMeasureId(product.getId(), unitOfMeasureId)
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "UNIDAD_NO_SOPORTADA", "El producto no admite registrar cantidades en esa unidad de medida."));
        return productUnit.getConversionFactorToBase();
    }

    /** BR-022 / docs/CRITICAL_FLOWS.md, sección 1.2: leer, validar, UPDATE condicionado, reintentar hasta 3 veces. */
    private void applyToInventory(Long productId, Long branchId, MovementDirection direction, BigDecimal quantityInBaseUnit) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Inventory inventory = findOrCreateInventory(productId, branchId);

            BigDecimal newQuantity;
            if (direction == MovementDirection.RETIRO) {
                if (inventory.getQuantityOnHand().compareTo(quantityInBaseUnit) < 0) {
                    throw new BusinessRuleViolationException(
                            "STOCK_INSUFICIENTE",
                            "No hay stock suficiente. Disponible: " + inventory.getQuantityOnHand() + ", solicitado: " + quantityInBaseUnit + ".");
                }
                newQuantity = inventory.getQuantityOnHand().subtract(quantityInBaseUnit);
            } else {
                newQuantity = inventory.getQuantityOnHand().add(quantityInBaseUnit);
            }

            int updated = inventoryRepository.applyQuantity(inventory.getId(), inventory.getVersion(), newQuantity, Instant.now());
            if (updated == 1) {
                stockAlertService.evaluate(inventory.getId(), branchId, productId, newQuantity, inventory.getMinimumStock());
                return;
            }
        }
        throw new ResourceConflictException(
                "CONFLICTO_CONCURRENCIA", "No se pudo aplicar el movimiento tras varios intentos por alta concurrencia sobre el mismo stock.");
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
}
