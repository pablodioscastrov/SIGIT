package com.inventario.multisucursal.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/API_DESIGN.md, sección 7.10 (RF-031 a RF-035). Sin {@code @PreAuthorize}
 * en la mayoría de las rutas: los tres roles pueden consultar el dashboard de
 * una sucursal (acotada a la propia para {@code OPERATOR}), la restricción
 * real la aplica {@link DashboardService} vía {@code AuthorizationService}
 * — mismo criterio que {@code ReportController}. La única ruta con
 * {@code @PreAuthorize} es la comparativa entre sucursales (RF-035, BR-043),
 * que no acepta ninguna sucursal individual y por tanto no tiene nada que
 * acotar a nivel de servicio.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/sales-summary")
    public SalesTrendResponse salesSummary(
            @RequestParam(required = false) Long branchId, @RequestParam(required = false) Integer months) {
        return dashboardService.salesTrend(branchId, months);
    }

    @GetMapping("/inventory-rotation")
    public InventoryDemandResponse inventoryRotation(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Integer months,
            @RequestParam(required = false) Integer limit) {
        return dashboardService.inventoryDemand(branchId, months, limit);
    }

    @GetMapping("/active-transfers")
    public ActiveTransfersResponse activeTransfers(@RequestParam(required = false) Long branchId) {
        return dashboardService.activeTransfers(branchId);
    }

    @GetMapping("/replenishment")
    public ReplenishmentResponse replenishment(
            @RequestParam(required = false) Long branchId, @RequestParam(required = false) Integer limit) {
        return dashboardService.replenishment(branchId, limit);
    }

    @GetMapping("/branch-comparison")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public BranchComparisonResponse branchComparison() {
        return dashboardService.branchComparison();
    }
}
