package com.inventario.multisucursal.reports;

import com.inventario.multisucursal.inventory.MovementReason;
import com.inventario.multisucursal.sales.SaleStatus;
import com.inventario.multisucursal.transfers.TransferStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * docs/API_DESIGN.md, sección 7.10. Sin {@code @PreAuthorize} en ningún
 * endpoint, incluidas las exportaciones nuevas (BR-056): los tres roles
 * pueden generar un reporte, y la restricción real de sucursal —un
 * {@code OPERATOR}/{@code MANAGER} solo exporta lo que ya podría ver en la
 * UI— la aplica cada servicio dueño del dato
 * ({@link com.inventario.multisucursal.inventory.InventoryMovementService},
 * {@link com.inventario.multisucursal.sales.SaleService},
 * {@link com.inventario.multisucursal.transfers.TransferService},
 * {@link LogisticsComplianceService}), nunca este controlador.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final LogisticsComplianceService logisticsComplianceService;
    private final ReportExportService reportExportService;

    public ReportController(LogisticsComplianceService logisticsComplianceService, ReportExportService reportExportService) {
        this.logisticsComplianceService = logisticsComplianceService;
        this.reportExportService = reportExportService;
    }

    @GetMapping("/logistics-compliance")
    public LogisticsComplianceResponse logisticsCompliance(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo) {
        return logisticsComplianceService.report(branchId, routeId, dateFrom, dateTo);
    }

    @GetMapping("/inventory-movements/export")
    public ResponseEntity<byte[]> exportInventoryMovements(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) MovementReason reason,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo) {
        return toDownload(reportExportService.exportInventoryMovements(branchId, productId, reason, dateFrom, dateTo));
    }

    @GetMapping("/sales/export")
    public ResponseEntity<byte[]> exportSales(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo) {
        return toDownload(reportExportService.exportSales(branchId, status, dateFrom, dateTo));
    }

    @GetMapping("/transfers/export")
    public ResponseEntity<byte[]> exportTransfers(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo) {
        return toDownload(reportExportService.exportTransfers(branchId, status, dateFrom, dateTo));
    }

    @GetMapping("/logistics-compliance/export")
    public ResponseEntity<byte[]> exportLogisticsCompliance(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo) {
        return toDownload(reportExportService.exportLogisticsCompliance(branchId, routeId, dateFrom, dateTo));
    }

    private ResponseEntity<byte[]> toDownload(ExportedFile file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(ExportedFile.XLSX_CONTENT_TYPE))
                .body(file.content());
    }
}
