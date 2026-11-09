package com.inventario.multisucursal.reports;

import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.products.ProductResponse;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.sales.SaleResponse;
import com.inventario.multisucursal.transfers.TransferItemRepository;
import com.inventario.multisucursal.transfers.TransferRepository;
import com.inventario.multisucursal.transfers.TransferResponse;
import com.inventario.multisucursal.transfers.TransferStatus;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reportes exportables en Excel (BR-056): rango válido, rango vacío,
 * permisos por sucursal, archivo no corrupto (se vuelve a abrir con Apache
 * POI) y datos esperados en las celdas. La generación del `.xlsx` en sí
 * ({@link ExcelReportWriter}) y la validación de rango
 * ({@link com.inventario.multisucursal.common.reports.ReportRangeValidator})
 * son compartidas por los cuatro reportes — se prueban a fondo aquí una vez
 * (con movimientos de inventario) y de forma más ligera en el resto, para no
 * repetir exactamente la misma cobertura cuatro veces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ReportExportApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";
    private static final String FUTURE_DATE = "2999-12-31";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferItemRepository transferItemRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Branch branchA;
    private Branch branchB;
    private UnitOfMeasure unUnit;
    private String adminToken;
    private String operatorAToken;
    private String operatorBToken;

    @BeforeEach
    void setUp() {
        transferItemRepository.deleteAll();
        transferRepository.deleteAll();
        inventoryRepository.deleteAll();
        unitOfMeasureRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branchA = branchRepository.save(new Branch("SUC-RP-A", "Sucursal A", null));
        branchB = branchRepository.save(new Branch("SUC-RP-B", "Sucursal B", null));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Operador A", "operator.a@test.local", hash, RoleCode.OPERATOR, branchA.getId()));
        userRepository.save(new User("Operador B", "operator.b@test.local", hash, RoleCode.OPERATOR, branchB.getId()));

        adminToken = login("admin@test.local");
        operatorAToken = login("operator.a@test.local");
        operatorBToken = login("operator.b@test.local");

        unUnit = unitOfMeasureRepository.save(new UnitOfMeasure("UN", "Unidad"));
    }

    // ==== Movimientos de inventario ====

    @Test
    void inventoryMovementsExportWithValidRangeContainsExpectedDataInAWellFormedFile() throws IOException {
        String productId = createProduct("SKU-RP-001");
        stockUp(productId, branchA.getId(), 7, "Carga de prueba para el reporte");

        ResponseEntity<byte[]> response = download(
                "/api/v1/reports/inventory-movements/export?branchId=" + branchA.getId() + rangeQuery(), adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo(ExportedFile.XLSX_CONTENT_TYPE);
        assertThat(response.getHeaders().getContentDisposition().getFilename()).endsWith(".xlsx").contains("movimientos-inventario");

        Sheet sheet = parseFirstSheet(response.getBody());
        List<String> dataRow = firstDataRowAfterHeader(sheet, "Fecha (UTC)");
        assertThat(dataRow).contains("Sucursal A").contains("AJUSTE_INGRESO");
        assertThat(dataRow.stream().anyMatch(cell -> cell.contains("Carga de prueba"))).isTrue();
    }

    @Test
    void inventoryMovementsExportWithEmptyRangeIsAWellFormedFileWithNoDataRows() throws IOException {
        String productId = createProduct("SKU-RP-002");
        stockUp(productId, branchA.getId(), 5, "Fuera del rango consultado");

        ResponseEntity<byte[]> response = download(
                "/api/v1/reports/inventory-movements/export?branchId=" + branchA.getId()
                        + "&dateFrom=2000-01-01T00:00:00Z&dateTo=2000-01-02T00:00:00Z",
                adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Sheet sheet = parseFirstSheet(response.getBody());
        String allText = allCellsAsText(sheet);
        assertThat(allText).contains("Sin resultados para los filtros aplicados.");
        assertThat(allText).doesNotContain("AJUSTE_INGRESO");
    }

    @Test
    void inventoryMovementsExportRequiresBothDates() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/reports/inventory-movements/export?branchId=" + branchA.getId(),
                HttpMethod.GET, new HttpEntity<>(authHeaders(adminToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"RANGO_FECHAS_REQUERIDO\"");
    }

    @Test
    void inventoryMovementsExportRejectsAnInvertedRange() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/reports/inventory-movements/export?branchId=" + branchA.getId()
                        + "&dateFrom=2026-01-31T00:00:00Z&dateTo=2026-01-01T00:00:00Z",
                HttpMethod.GET, new HttpEntity<>(authHeaders(adminToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"RANGO_FECHAS_INVALIDO\"");
    }

    @Test
    void operatorCannotExportAnotherBranchesInventoryMovements() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/reports/inventory-movements/export?branchId=" + branchB.getId() + rangeQuery(),
                HttpMethod.GET, new HttpEntity<>(authHeaders(operatorAToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");
    }

    @Test
    void operatorExportingWithoutABranchFilterOnlySeesTheirOwnBranch() throws IOException {
        String productId = createProduct("SKU-RP-003");
        stockUp(productId, branchA.getId(), 4, "Movimiento de la sucursal A");

        ResponseEntity<byte[]> response = download("/api/v1/reports/inventory-movements/export?" + rangeQuery().substring(1), operatorAToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Sheet sheet = parseFirstSheet(response.getBody());
        assertThat(allCellsAsText(sheet)).contains("Sucursal A").doesNotContain("Sucursal B");
    }

    // ==== Ventas ====

    @Test
    void salesExportWithValidRangeContainsExpectedData() throws IOException {
        String productId = createProduct("SKU-RP-010");
        stockUp(productId, branchA.getId(), 20, "Carga inicial");
        sell(productId, branchA.getId(), 3, operatorAToken);

        ResponseEntity<byte[]> response = download(
                "/api/v1/reports/sales/export?branchId=" + branchA.getId() + rangeQuery(), adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Sheet sheet = parseFirstSheet(response.getBody());
        List<String> dataRow = firstDataRowAfterHeader(sheet, "Fecha (UTC)");
        assertThat(dataRow).contains("Sucursal A").contains("Operador A");
        assertThat(dataRow.stream().anyMatch(cell -> cell.startsWith("V-"))).isTrue();
    }

    @Test
    void operatorCannotExportAnotherBranchesSales() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/reports/sales/export?branchId=" + branchB.getId() + rangeQuery(),
                HttpMethod.GET, new HttpEntity<>(authHeaders(operatorAToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");
    }

    // ==== Transferencias ====

    @Test
    void transfersExportWithValidRangeContainsExpectedData() throws IOException {
        String productId = createProduct("SKU-RP-020");
        stockUp(productId, branchA.getId(), 50, "Carga inicial");
        requestTransfer(productId, branchA, branchB, 5);

        ResponseEntity<byte[]> response = download(
                "/api/v1/reports/transfers/export?branchId=" + branchA.getId() + rangeQuery(), adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Sheet sheet = parseFirstSheet(response.getBody());
        List<String> dataRow = firstDataRowAfterHeader(sheet, "Solicitada (UTC)");
        assertThat(dataRow).contains("Sucursal A").contains("Sucursal B").contains("REQUESTED");
    }

    @Test
    void transfersExportWithEmptyRangeIsAWellFormedFileWithNoDataRows() throws IOException {
        String productId = createProduct("SKU-RP-021");
        stockUp(productId, branchA.getId(), 10, "Carga inicial");
        requestTransfer(productId, branchA, branchB, 2);

        ResponseEntity<byte[]> response = download(
                "/api/v1/reports/transfers/export?branchId=" + branchA.getId()
                        + "&dateFrom=2000-01-01T00:00:00Z&dateTo=2000-01-02T00:00:00Z",
                adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allCellsAsText(parseFirstSheet(response.getBody()))).contains("Sin resultados para los filtros aplicados.");
    }

    // ==== Cumplimiento logístico ====

    @Test
    void logisticsComplianceExportContainsSummaryAndByRouteRows() throws IOException {
        String productId = createProduct("SKU-RP-030");
        stockUp(productId, branchA.getId(), 50, "Carga inicial");
        deliver(productId, branchA, branchB, 10, FUTURE_DATE, 10);

        ResponseEntity<byte[]> response = download(
                "/api/v1/reports/logistics-compliance/export?branchId=" + branchA.getId() + rangeQuery(), adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Sheet sheet = parseFirstSheet(response.getBody());
        String allText = allCellsAsText(sheet);
        assertThat(allText).contains("Total (todas las rutas en el alcance)");
        assertThat(allText).contains("Sucursal A → Sucursal B");
    }

    @Test
    void logisticsComplianceExportRequiresBothDates() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/reports/logistics-compliance/export?branchId=" + branchA.getId(),
                HttpMethod.GET, new HttpEntity<>(authHeaders(adminToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"RANGO_FECHAS_REQUERIDO\"");
    }

    @Test
    void operatorCannotExportAnotherBranchesLogisticsCompliance() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/reports/logistics-compliance/export?branchId=" + branchB.getId() + rangeQuery(),
                HttpMethod.GET, new HttpEntity<>(authHeaders(operatorAToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- helpers ----

    private String rangeQuery() {
        return "&dateFrom=2020-01-01T00:00:00Z&dateTo=2999-12-31T23:59:59Z";
    }

    private ResponseEntity<byte[]> download(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), byte[].class);
    }

    private Sheet parseFirstSheet(byte[] content) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            return workbook.getSheetAt(0);
        }
    }

    /** Primera fila de datos después de la fila de encabezados (identificada por su primera celda). */
    private List<String> firstDataRowAfterHeader(Sheet sheet, String firstHeaderCell) {
        int headerRowIndex = -1;
        for (Row row : sheet) {
            if (row.getCell(0) != null && firstHeaderCell.equals(cellAsText(row.getCell(0)))) {
                headerRowIndex = row.getRowNum();
                break;
            }
        }
        assertThat(headerRowIndex).as("fila de encabezado '%s' encontrada", firstHeaderCell).isGreaterThanOrEqualTo(0);
        Row dataRow = sheet.getRow(headerRowIndex + 1);
        assertThat(dataRow).as("hay al menos una fila de datos tras el encabezado").isNotNull();
        return StreamSupport.stream(dataRow.spliterator(), false).map(this::cellAsText).collect(Collectors.toList());
    }

    private String allCellsAsText(Sheet sheet) {
        StringBuilder builder = new StringBuilder();
        for (Row row : sheet) {
            for (org.apache.poi.ss.usermodel.Cell cell : row) {
                builder.append(cellAsText(cell)).append(" | ");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private String cellAsText(org.apache.poi.ss.usermodel.Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toString()
                    : String.valueOf(cell.getNumericCellValue());
            case BLANK -> "";
            default -> cell.toString();
        };
    }

    private String createProduct(String sku) {
        return restTemplate.exchange("/api/v1/products", HttpMethod.POST,
                new HttpEntity<>(Map.of("sku", sku, "name", "Producto " + sku, "baseUnitOfMeasureId", unUnit.getId(), "minimumStock", 0, "unitPrice", 10),
                        authHeaders(adminToken)),
                ProductResponse.class).getBody().id();
    }

    private void stockUp(String productId, Long branchId, int quantity, String notes) {
        restTemplate.exchange("/api/v1/inventory/adjustments", HttpMethod.POST,
                new HttpEntity<>(Map.of("branchId", branchId, "productId", Long.valueOf(productId),
                        "direction", "INGRESO", "quantity", quantity, "notes", notes),
                        authHeaders(adminToken)),
                Object.class);
    }

    private void sell(String productId, Long branchId, int quantity, String token) {
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<SaleResponse> response = restTemplate.exchange("/api/v1/sales", HttpMethod.POST,
                new HttpEntity<>(Map.of("branchId", branchId, "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", quantity))), headers),
                SaleResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private TransferResponse requestTransfer(String productId, Branch from, Branch to, int quantity) {
        HttpHeaders headers = authHeaders(adminToken);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(Map.of("originBranchId", from.getId(), "destinationBranchId", to.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityRequested", quantity))), headers),
                TransferResponse.class).getBody();
    }

    private void approveTransfer(TransferResponse transfer, int quantity) {
        restTemplate.exchange("/api/v1/transfers/" + transfer.id() + "/approve", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", List.of(
                        Map.of("transferItemId", Long.valueOf(transfer.items().get(0).id()), "quantityApproved", quantity))),
                        authHeaders(adminToken)),
                TransferResponse.class);
    }

    private TransferResponse dispatchTransfer(TransferResponse transfer, int quantity, String estimatedArrivalDate) {
        return restTemplate.exchange("/api/v1/transfers/" + transfer.id() + "/dispatch", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrierName", "Transportes XYZ", "estimatedArrivalDate", estimatedArrivalDate,
                        "items", List.of(Map.of("transferItemId", Long.valueOf(transfer.items().get(0).id()), "quantityShipped", quantity))),
                        authHeaders(adminToken)),
                TransferResponse.class).getBody();
    }

    private void deliver(String productId, Branch from, Branch to, int shipped, String estimatedArrivalDate, int received) {
        TransferResponse transfer = requestTransfer(productId, from, to, shipped);
        approveTransfer(transfer, shipped);
        TransferResponse dispatched = dispatchTransfer(transfer, shipped, estimatedArrivalDate);
        TransferResponse result = restTemplate.exchange("/api/v1/transfers/" + dispatched.id() + "/receive", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", List.of(
                        Map.of("transferItemId", Long.valueOf(dispatched.items().get(0).id()), "quantityReceived", received))),
                        authHeaders(adminToken)),
                TransferResponse.class).getBody();
        assertThat(result.status()).isIn(TransferStatus.RECEIVED_COMPLETE, TransferStatus.RECEIVED_PARTIAL);
    }

    private String login(String email) {
        var body = new com.inventario.multisucursal.auth.LoginRequest(email, SEED_PASSWORD);
        return restTemplate.postForEntity("/api/v1/auth/login", body, com.inventario.multisucursal.auth.LoginResponse.class)
                .getBody().accessToken();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
