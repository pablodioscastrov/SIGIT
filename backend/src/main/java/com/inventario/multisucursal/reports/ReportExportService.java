package com.inventario.multisucursal.reports;

import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.reports.ReportRangeValidator;
import com.inventario.multisucursal.inventory.InventoryMovementResponse;
import com.inventario.multisucursal.inventory.InventoryMovementService;
import com.inventario.multisucursal.inventory.MovementReason;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.reports.ReportSheet.ColumnType;
import com.inventario.multisucursal.reports.ReportSheet.ReportColumn;
import com.inventario.multisucursal.sales.SaleResponse;
import com.inventario.multisucursal.sales.SaleService;
import com.inventario.multisucursal.sales.SaleStatus;
import com.inventario.multisucursal.transfers.TransferResponse;
import com.inventario.multisucursal.transfers.TransferService;
import com.inventario.multisucursal.transfers.TransferStatus;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orquesta los cuatro reportes exportables (BR-056): cada método delega la
 * consulta acotada y la autorización de sucursal al servicio dueño del dato
 * ({@link InventoryMovementService}, {@link SaleService},
 * {@link TransferService}, {@link LogisticsComplianceService} — este último
 * ya existente, sin cambios), resuelve aquí los nombres legibles que la UI
 * ya muestra (sucursal, producto, responsable — los mismos que
 * {@code branchesById}/{@code productsById} resuelven en el cliente, para
 * que el archivo diga exactamente lo mismo que la pantalla) y arma un
 * {@link ReportSheet} neutral que {@link ExcelReportWriter} convierte a
 * bytes. Ninguna consulta ni regla de autorización vive aquí — este
 * servicio es hoja del grafo de dependencias, igual que
 * {@link LogisticsComplianceService} (docs/ARCHITECTURE.md, sección 4).
 */
@Service
public class ReportExportService {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** Patrón fijo, no dependiente de configuración regional — mismo criterio de claridad que las columnas de fecha del propio archivo. */
    private static final DateTimeFormatter METADATA_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final InventoryMovementService inventoryMovementService;
    private final SaleService saleService;
    private final TransferService transferService;
    private final LogisticsComplianceService logisticsComplianceService;
    private final ExcelReportWriter excelReportWriter;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final UserRepository userRepository;

    public ReportExportService(
            InventoryMovementService inventoryMovementService,
            SaleService saleService,
            TransferService transferService,
            LogisticsComplianceService logisticsComplianceService,
            ExcelReportWriter excelReportWriter,
            BranchRepository branchRepository,
            ProductRepository productRepository,
            UnitOfMeasureRepository unitOfMeasureRepository,
            UserRepository userRepository) {
        this.inventoryMovementService = inventoryMovementService;
        this.saleService = saleService;
        this.transferService = transferService;
        this.logisticsComplianceService = logisticsComplianceService;
        this.excelReportWriter = excelReportWriter;
        this.branchRepository = branchRepository;
        this.productRepository = productRepository;
        this.unitOfMeasureRepository = unitOfMeasureRepository;
        this.userRepository = userRepository;
    }

    public ExportedFile exportInventoryMovements(Long branchId, Long productId, MovementReason reason, Instant dateFrom, Instant dateTo) {
        List<InventoryMovementResponse> rows = inventoryMovementService.listForExport(branchId, productId, reason, dateFrom, dateTo);

        Map<Long, String> branchNames = branchNames(idsOf(rows, InventoryMovementResponse::branchId));
        Map<Long, String> productLabels = productLabels(idsOf(rows, InventoryMovementResponse::productId));
        Map<Long, String> unitCodes = unitCodes(idsOf(rows, InventoryMovementResponse::unitOfMeasureId));
        Map<Long, String> userNames = userNames(idsOf(rows, InventoryMovementResponse::responsibleUserId));

        List<Object[]> excelRows = rows.stream()
                .map(row -> new Object[] {
                        row.occurredAt(),
                        branchNames.getOrDefault(Long.valueOf(row.branchId()), row.branchId()),
                        productLabels.getOrDefault(Long.valueOf(row.productId()), "Producto " + row.productId()),
                        row.direction().name(),
                        row.reason().name(),
                        row.quantity(),
                        unitCodes.getOrDefault(Long.valueOf(row.unitOfMeasureId()), row.unitOfMeasureId()),
                        userNames.getOrDefault(Long.valueOf(row.responsibleUserId()), row.responsibleUserId()),
                        row.notes(),
                })
                .toList();

        ReportSheet sheet = new ReportSheet(
                "Movimientos de inventario",
                metadataLines(dateFrom, dateTo, branchId != null ? branchNames.get(branchId) : "Todas", rows.size()),
                List.of(
                        new ReportColumn("Fecha (UTC)", ColumnType.DATETIME),
                        new ReportColumn("Sucursal", ColumnType.TEXT),
                        new ReportColumn("Producto", ColumnType.TEXT),
                        new ReportColumn("Dirección", ColumnType.TEXT),
                        new ReportColumn("Motivo", ColumnType.TEXT),
                        new ReportColumn("Cantidad", ColumnType.NUMBER),
                        new ReportColumn("Unidad", ColumnType.TEXT),
                        new ReportColumn("Responsable", ColumnType.TEXT),
                        new ReportColumn("Notas", ColumnType.TEXT)),
                excelRows);

        return toFile("movimientos-inventario", sheet);
    }

    public ExportedFile exportSales(Long branchId, SaleStatus status, Instant dateFrom, Instant dateTo) {
        List<SaleResponse> rows = saleService.listForExport(branchId, status, dateFrom, dateTo);
        Map<Long, String> branchNames = branchNames(idsOf(rows, SaleResponse::branchId));

        List<Object[]> excelRows = rows.stream()
                .map(row -> new Object[] {
                        row.saleDate(),
                        row.saleNumber(),
                        branchNames.getOrDefault(Long.valueOf(row.branchId()), row.branchId()),
                        row.soldByUserName() != null ? row.soldByUserName() : row.soldByUserId(),
                        row.items().size(),
                        row.subtotal(),
                        row.discountTotal(),
                        row.total(),
                })
                .toList();

        ReportSheet sheet = new ReportSheet(
                "Ventas",
                metadataLines(dateFrom, dateTo, branchId != null ? branchNames.get(branchId) : "Todas", rows.size()),
                List.of(
                        new ReportColumn("Fecha (UTC)", ColumnType.DATETIME),
                        new ReportColumn("Número", ColumnType.TEXT),
                        new ReportColumn("Sucursal", ColumnType.TEXT),
                        new ReportColumn("Responsable", ColumnType.TEXT),
                        new ReportColumn("Líneas", ColumnType.NUMBER),
                        new ReportColumn("Subtotal", ColumnType.MONEY),
                        new ReportColumn("Descuento", ColumnType.MONEY),
                        new ReportColumn("Total", ColumnType.MONEY)),
                excelRows);

        return toFile("ventas", sheet);
    }

    public ExportedFile exportTransfers(Long branchId, TransferStatus status, Instant dateFrom, Instant dateTo) {
        List<TransferResponse> rows = transferService.listForExport(branchId, status, dateFrom, dateTo);
        List<Long> branchIds = new ArrayList<>();
        rows.forEach(row -> {
            branchIds.add(Long.valueOf(row.originBranchId()));
            branchIds.add(Long.valueOf(row.destinationBranchId()));
        });
        Map<Long, String> branchNames = branchNames(branchIds.stream().distinct().toList());

        List<Object[]> excelRows = rows.stream()
                .map(row -> new Object[] {
                        row.requestedAt(),
                        row.transferNumber(),
                        row.status().name(),
                        branchNames.getOrDefault(Long.valueOf(row.originBranchId()), row.originBranchId()),
                        branchNames.getOrDefault(Long.valueOf(row.destinationBranchId()), row.destinationBranchId()),
                        row.urgency() ? "Sí" : "No",
                        row.carrierName(),
                        row.estimatedArrivalDate(),
                        row.dispatchedAt(),
                        row.receivedAt(),
                })
                .toList();

        ReportSheet sheet = new ReportSheet(
                "Transferencias",
                metadataLines(dateFrom, dateTo, branchId != null ? branchNames.get(branchId) : "Todas", rows.size()),
                List.of(
                        new ReportColumn("Solicitada (UTC)", ColumnType.DATETIME),
                        new ReportColumn("Número", ColumnType.TEXT),
                        new ReportColumn("Estado", ColumnType.TEXT),
                        new ReportColumn("Origen", ColumnType.TEXT),
                        new ReportColumn("Destino", ColumnType.TEXT),
                        new ReportColumn("Urgente", ColumnType.TEXT),
                        new ReportColumn("Transportista", ColumnType.TEXT),
                        new ReportColumn("Llegada estimada", ColumnType.DATE),
                        new ReportColumn("Despachada (UTC)", ColumnType.DATETIME),
                        new ReportColumn("Recibida (UTC)", ColumnType.DATETIME)),
                excelRows);

        return toFile("transferencias", sheet);
    }

    public ExportedFile exportLogisticsCompliance(Long branchId, Long routeId, Instant dateFrom, Instant dateTo) {
        // A diferencia de GET /reports/logistics-compliance (que sí acepta un
        // rango abierto para la pantalla interactiva), la exportación exige el
        // rango explícito — igual criterio que el resto de reportes (BR-056):
        // `LogisticsComplianceService.report` en sí no acota cuántas
        // transferencias agrega para calcular el resumen.
        ReportRangeValidator.requireValidRange(dateFrom, dateTo);
        LogisticsComplianceResponse report = logisticsComplianceService.report(branchId, routeId, dateFrom, dateTo);

        List<Long> branchIds = new ArrayList<>();
        report.byRoute().forEach(route -> {
            branchIds.add(Long.valueOf(route.originBranchId()));
            branchIds.add(Long.valueOf(route.destinationBranchId()));
        });
        Map<Long, String> branchNames = branchNames(branchIds.stream().distinct().toList());

        List<Object[]> excelRows = new ArrayList<>();
        excelRows.add(metricsRow("Total (todas las rutas en el alcance)", null, report.summary()));
        for (LogisticsComplianceResponse.RouteCompliance route : report.byRoute()) {
            String scopeLabel = branchNames.getOrDefault(Long.valueOf(route.originBranchId()), route.originBranchId())
                    + " → " + branchNames.getOrDefault(Long.valueOf(route.destinationBranchId()), route.destinationBranchId());
            excelRows.add(metricsRow(scopeLabel, route.classification() != null ? route.classification().name() : "Sin clasificar", route.metrics()));
        }

        String appliedBranchLabel = report.appliedFilters().branchId() != null
                ? branchNames.getOrDefault(Long.valueOf(report.appliedFilters().branchId()), report.appliedFilters().branchId())
                : "Todas";

        ReportSheet sheet = new ReportSheet(
                "Cumplimiento logístico",
                metadataLines(dateFrom, dateTo, appliedBranchLabel, report.byRoute().size()),
                List.of(
                        new ReportColumn("Ámbito", ColumnType.TEXT),
                        new ReportColumn("Clasificación", ColumnType.TEXT),
                        new ReportColumn("Despachadas", ColumnType.NUMBER),
                        new ReportColumn("Entregadas", ColumnType.NUMBER),
                        new ReportColumn("En tránsito", ColumnType.NUMBER),
                        new ReportColumn("Atrasadas en curso", ColumnType.NUMBER),
                        new ReportColumn("A tiempo", ColumnType.NUMBER),
                        new ReportColumn("Tardías", ColumnType.NUMBER),
                        new ReportColumn("No evaluables", ColumnType.NUMBER),
                        new ReportColumn("Con faltante", ColumnType.NUMBER),
                        new ReportColumn("% Cumplimiento", ColumnType.NUMBER),
                        new ReportColumn("Horas promedio de entrega", ColumnType.NUMBER)),
                excelRows);

        return toFile("cumplimiento-logistico", sheet);
    }

    private Object[] metricsRow(String scopeLabel, String classification, LogisticsComplianceResponse.ComplianceMetrics metrics) {
        return new Object[] {
                scopeLabel, classification,
                metrics.dispatched(), metrics.delivered(), metrics.inTransit(), metrics.overdueInTransit(),
                metrics.onTime(), metrics.late(), metrics.notEvaluable(), metrics.withShortages(),
                metrics.complianceRate(), metrics.averageDeliveryHours(),
        };
    }

    private List<String> metadataLines(Instant dateFrom, Instant dateTo, String branchLabel, int rowCount) {
        return List.of(
                "Rango: " + METADATA_TIMESTAMP.format(dateFrom) + " a " + METADATA_TIMESTAMP.format(dateTo) + " (UTC)",
                "Sucursal: " + (branchLabel != null ? branchLabel : "—"),
                "Filas: " + rowCount,
                "Generado: " + METADATA_TIMESTAMP.format(Instant.now()) + " (UTC)");
    }

    private ExportedFile toFile(String baseName, ReportSheet sheet) {
        byte[] content = excelReportWriter.write(sheet);
        String filename = baseName + "-" + FILE_TIMESTAMP.format(ZonedDateTime.now(ZoneOffset.UTC)) + ".xlsx";
        return new ExportedFile(filename, content);
    }

    private <T> List<Long> idsOf(List<T> rows, Function<T, String> idExtractor) {
        return rows.stream().map(idExtractor).map(Long::valueOf).distinct().toList();
    }

    private Map<Long, String> branchNames(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return branchRepository.findAllById(ids).stream().collect(Collectors.toMap(Branch::getId, Branch::getName));
    }

    private Map<Long, String> productLabels(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, product -> product.getSku() + " — " + product.getName()));
    }

    private Map<Long, String> unitCodes(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return unitOfMeasureRepository.findAllById(ids).stream().collect(Collectors.toMap(UnitOfMeasure::getId, UnitOfMeasure::getCode));
    }

    private Map<Long, String> userNames(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, User::getName));
    }
}
