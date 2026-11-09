package com.inventario.multisucursal.transfers;

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

/**
 * docs/API_DESIGN.md, sección 7.9. La autorización tiene dos mitades: el rol
 * se controla aquí con {@code @PreAuthorize}, y la sucursal correcta para
 * cada acción (origen para aprobar/despachar, destino para solicitar/recibir)
 * dentro de {@link TransferService}, que es donde se conoce la transferencia.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping
    public PageResponse<TransferResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) TransferStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return transferService.list(branchId, role, status, pageable);
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable Long id) {
        return transferService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER', 'ADMIN')")
    public TransferResponse request(
            @Valid @RequestBody CreateTransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transferService.request(request, principal.userId(), idempotencyKey);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public TransferResponse approve(
            @PathVariable Long id,
            @Valid @RequestBody ApproveTransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transferService.approve(id, request, principal.userId());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public TransferResponse reject(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        return transferService.reject(id, principal.userId());
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER', 'ADMIN')")
    public TransferResponse dispatch(
            @PathVariable Long id,
            @Valid @RequestBody DispatchTransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transferService.dispatch(id, request, principal.userId());
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER', 'ADMIN')")
    public TransferResponse receive(
            @PathVariable Long id,
            @Valid @RequestBody ReceiveTransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transferService.receive(id, request, principal.userId());
    }

    @PostMapping("/{id}/items/{itemId}/discrepancy-treatment")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public DiscrepancyTreatmentResponse applyDiscrepancyTreatment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody ApplyDiscrepancyTreatmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transferService.applyDiscrepancyTreatment(id, itemId, request, principal.userId());
    }
}
