package com.inventario.multisucursal.inventory;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** docs/API_DESIGN.md, sección 7.5. */
@RestController
@RequestMapping("/api/v1")
public class InventoryMovementController {

    private final InventoryMovementService movementService;

    public InventoryMovementController(InventoryMovementService movementService) {
        this.movementService = movementService;
    }

    @GetMapping("/inventory-movements")
    public PageResponse<InventoryMovementResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) MovementReason reason,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo,
            @PageableDefault(size = 20, sort = "occurredAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        return movementService.list(branchId, productId, reason, dateFrom, dateTo, pageable);
    }

    @PostMapping("/inventory/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public InventoryMovementResponse createAdjustment(
            @Valid @RequestBody InventoryAdjustmentRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        return movementService.createAdjustment(request, principal.userId());
    }
}
