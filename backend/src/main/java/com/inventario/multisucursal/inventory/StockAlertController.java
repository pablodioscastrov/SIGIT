package com.inventario.multisucursal.inventory;

import com.inventario.multisucursal.common.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/API_DESIGN.md, sección 6: lectura abierta a cualquier rol autenticado,
 * cualquier sucursal — mismo criterio que `inventory`, del que esta alerta se
 * deriva directamente (RF-003). Sin escritura: se generan y resuelven
 * automáticamente (ver {@link StockAlertService#evaluate}), nunca por una
 * acción directa del usuario.
 */
@RestController
@RequestMapping("/api/v1/stock-alerts")
public class StockAlertController {

    private final StockAlertService stockAlertService;

    public StockAlertController(StockAlertService stockAlertService) {
        this.stockAlertService = stockAlertService;
    }

    @GetMapping
    public PageResponse<StockAlertResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) StockAlertStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return stockAlertService.list(branchId, status, pageable);
    }
}
