package com.inventario.multisucursal.products;

import com.inventario.multisucursal.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** docs/API_DESIGN.md, sección 7.8. */
@RestController
@RequestMapping("/api/v1/price-lists")
public class PriceListController {

    private final PriceListService priceListService;

    public PriceListController(PriceListService priceListService) {
        this.priceListService = priceListService;
    }

    @GetMapping
    public PageResponse<PriceListResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable) {
        return priceListService.list(branchId, active, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListResponse create(@Valid @RequestBody CreatePriceListRequest request) {
        return priceListService.create(request);
    }

    @GetMapping("/{id}/prices")
    public List<PriceResponse> listPrices(
            @PathVariable Long id, @RequestParam(required = false, defaultValue = "false") boolean includeHistory) {
        return priceListService.listPrices(id, includeHistory);
    }

    @PostMapping("/{id}/prices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public PriceResponse setPrice(
            @PathVariable Long id,
            @Valid @RequestBody SetPriceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return priceListService.setPrice(id, request, idempotencyKey);
    }
}
