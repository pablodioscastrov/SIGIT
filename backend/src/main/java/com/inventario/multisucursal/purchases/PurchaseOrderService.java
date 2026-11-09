package com.inventario.multisucursal.purchases;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.auth.AuthorizationService;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.BadRequestException;
import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.common.web.PageResponse;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.suppliers.Supplier;
import com.inventario.multisucursal.suppliers.SupplierRepository;
import com.inventario.multisucursal.users.RoleCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ciclo de vida de la orden de compra (RF-012, RF-013): creación con líneas,
 * consulta y cancelación. La recepción (flujo B) vive en
 * {@link PurchaseReceiptService} — es lo bastante compleja (atomicidad,
 * bloqueo optimista doble, idempotencia) para merecer su propio servicio,
 * igual que {@code ProductUnitService} se separó de {@code ProductService}.
 */
@Service
public class PurchaseOrderService {

    /** Dinero: 4 decimales, redondeo HALF_UP (docs/API_DESIGN.md, sección 1: "hasta 4 decimales"). */
    static final int MONEY_SCALE = 4;
    static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final AuthorizationService authorizationService;

    public PurchaseOrderService(
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderItemRepository purchaseOrderItemRepository,
            SupplierRepository supplierRepository,
            BranchRepository branchRepository,
            ProductRepository productRepository,
            AuthorizationService authorizationService) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.supplierRepository = supplierRepository;
        this.branchRepository = branchRepository;
        this.productRepository = productRepository;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public PurchaseOrderResponse create(CreatePurchaseOrderRequest request, Long createdByUserId, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        authorizationService.requireBranchAccess(request.branchId());

        Supplier supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new ResourceNotFoundException("PROVEEDOR_NO_ENCONTRADO", "Proveedor no encontrado."));
        if (!supplier.isActive()) {
            throw new ResourceConflictException("PROVEEDOR_INACTIVO", "El proveedor está inactivo.");
        }
        Branch branch = branchRepository.findById(request.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("SUCURSAL_NO_ENCONTRADA", "Sucursal no encontrada."));
        if (!branch.isActive()) {
            throw new ResourceConflictException("SUCURSAL_INACTIVA", "La sucursal está inactiva.");
        }

        Set<Long> seenProducts = new HashSet<>();
        PurchaseOrder order = purchaseOrderRepository.save(
                new PurchaseOrder(generateOrderNumber(), supplier.getId(), branch.getId(), request.paymentTerm(), createdByUserId));

        List<PurchaseOrderItem> items = request.items().stream()
                .map(itemRequest -> buildItem(order.getId(), itemRequest, seenProducts))
                .map(purchaseOrderItemRepository::save)
                .toList();

        return PurchaseOrderResponse.from(order, items);
    }

    public PurchaseOrderResponse getById(Long id) {
        PurchaseOrder order = findOrThrow(id);
        authorizationService.requireBranchAccess(order.getBranchId());
        return PurchaseOrderResponse.from(order, purchaseOrderItemRepository.findByPurchaseOrderId(order.getId()));
    }

    public PageResponse<PurchaseOrderResponse> list(Long branchId, Long supplierId, PurchaseOrderStatus status, Pageable pageable) {
        Long effectiveBranchId = currentUser().role() == RoleCode.ADMIN ? branchId : currentUser().branchId();
        Page<PurchaseOrder> page = purchaseOrderRepository.search(effectiveBranchId, supplierId, status, pageable);
        return PageResponse.from(page.map(order -> PurchaseOrderResponse.from(order, purchaseOrderItemRepository.findByPurchaseOrderId(order.getId()))));
    }

    @Transactional
    public PurchaseOrderResponse cancel(Long id) {
        PurchaseOrder order = findOrThrow(id);
        authorizationService.requireBranchAccess(order.getBranchId());
        if (order.getStatus() != PurchaseOrderStatus.CREATED) {
            throw new ResourceConflictException("TRANSICION_INVALIDA", "Solo se puede cancelar una orden en estado CREATED sin recepciones.");
        }
        order.cancel();
        return PurchaseOrderResponse.from(order, purchaseOrderItemRepository.findByPurchaseOrderId(order.getId()));
    }

    private PurchaseOrderItem buildItem(Long purchaseOrderId, CreatePurchaseOrderItemRequest itemRequest, Set<Long> seenProducts) {
        if (!seenProducts.add(itemRequest.productId())) {
            throw new BusinessRuleViolationException("PRODUCTO_DUPLICADO_EN_ORDEN", "Un producto no puede repetirse en la misma orden de compra.");
        }
        if (itemRequest.quantityOrdered().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleViolationException("CANTIDAD_INVALIDA", "La cantidad ordenada debe ser mayor que cero.");
        }
        Product product = productRepository.findById(itemRequest.productId())
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado."));
        if (!product.isActive()) {
            throw new ResourceConflictException("PRODUCTO_INACTIVO", "El producto está inactivo.");
        }
        Long unitOfMeasureId = itemRequest.unitOfMeasureId() != null ? itemRequest.unitOfMeasureId() : product.getBaseUnitOfMeasureId();

        BigDecimal discount = itemRequest.discountPercentage() != null ? itemRequest.discountPercentage() : BigDecimal.ZERO;
        BigDecimal subtotal = itemRequest.unitPrice().multiply(itemRequest.quantityOrdered());
        BigDecimal discountAmount = subtotal.multiply(discount).divide(BigDecimal.valueOf(100), MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal lineTotal = subtotal.subtract(discountAmount).setScale(MONEY_SCALE, MONEY_ROUNDING);

        return new PurchaseOrderItem(
                purchaseOrderId, product.getId(), unitOfMeasureId, itemRequest.quantityOrdered(), itemRequest.unitPrice(), discount, lineTotal);
    }

    private String generateOrderNumber() {
        return "OC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * Exige la presencia del encabezado (docs/API_DESIGN.md, sección 2) pero
     * no deduplica reintentos de creación: a diferencia de la recepción
     * (categoría 2 con {@code InventoryMovement.idempotency_key} real,
     * ver {@link PurchaseReceiptService}), la creación de una orden no tiene
     * una columna de idempotencia prevista en el modelo aprobado
     * (docs/BUSINESS_RULES.md solo señala pendiente la de recepción/ajuste) y
     * no está entre las pruebas mínimas pedidas para este módulo — queda
     * documentado como limitación conocida, no como comportamiento silencioso.
     */
    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUERIDO", "El encabezado Idempotency-Key es obligatorio.");
        }
    }

    private PurchaseOrder findOrThrow(Long id) {
        return purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ORDEN_COMPRA_NO_ENCONTRADA", "Orden de compra no encontrada."));
    }

    private AuthenticatedUser currentUser() {
        return (AuthenticatedUser) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
