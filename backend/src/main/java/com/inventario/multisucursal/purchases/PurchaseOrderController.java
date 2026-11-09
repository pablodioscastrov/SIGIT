package com.inventario.multisucursal.purchases;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/API_DESIGN.md, sección 7.7. Lectura: cualquier rol, acotada a la propia sucursal salvo ADMIN (sección 6). */
@RestController
@RequestMapping("/api/v1/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseReceiptService purchaseReceiptService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService, PurchaseReceiptService purchaseReceiptService) {
        this.purchaseOrderService = purchaseOrderService;
        this.purchaseReceiptService = purchaseReceiptService;
    }

    @GetMapping
    public PageResponse<PurchaseOrderResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) PurchaseOrderStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return purchaseOrderService.list(branchId, supplierId, status, pageable);
    }

    @GetMapping("/{id}")
    public PurchaseOrderResponse get(@PathVariable Long id) {
        return purchaseOrderService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER', 'ADMIN')")
    public PurchaseOrderResponse create(
            @Valid @RequestBody CreatePurchaseOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return purchaseOrderService.create(request, principal.userId(), idempotencyKey);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER', 'ADMIN')")
    public PurchaseOrderResponse cancel(@PathVariable Long id) {
        return purchaseOrderService.cancel(id);
    }

    @PostMapping("/{id}/receipts")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER', 'ADMIN')")
    public PurchaseReceiptResponse receive(
            @PathVariable Long id,
            @Valid @RequestBody PurchaseReceiptRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return purchaseReceiptService.receive(id, request, idempotencyKey, principal.userId());
    }
}
