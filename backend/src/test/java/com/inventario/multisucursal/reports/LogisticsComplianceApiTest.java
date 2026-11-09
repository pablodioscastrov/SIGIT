package com.inventario.multisucursal.reports;

import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.logistics.RouteClassification;
import com.inventario.multisucursal.logistics.RouteRepository;
import com.inventario.multisucursal.logistics.RouteResponse;
import com.inventario.multisucursal.products.ProductResponse;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.transfers.TransferItemRepository;
import com.inventario.multisucursal.transfers.TransferRepository;
import com.inventario.multisucursal.transfers.TransferResponse;
import com.inventario.multisucursal.transfers.TransferStatus;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cumplimiento logístico (RF-027, RF-030): estimado vs. real, filtros por
 * sucursal y ruta, y permisos. Todo se ejerce por HTTP y con transferencias
 * reales — las métricas se derivan de los timestamps que el propio flujo de
 * transferencias persiste, así que no hay forma de "sembrar" un resultado
 * sin pasar por el ciclo completo.
 *
 * <p>Fechas estimadas: una muy futura produce una entrega puntual y una ya
 * pasada produce una tardía, porque la recepción ocurre "hoy" durante la
 * prueba.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class LogisticsComplianceApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";
    private static final String FUTURE_DATE = "2999-12-31";
    private static final String PAST_DATE = "2000-01-01";

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
    private RouteRepository routeRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferItemRepository transferItemRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Branch origin;
    private Branch destination;
    private Branch otherDestination;
    private UnitOfMeasure unUnit;
    private String adminToken;
    private String managerToken;
    private String originOperatorToken;

    @BeforeEach
    void setUp() {
        transferItemRepository.deleteAll();
        transferRepository.deleteAll();
        routeRepository.deleteAll();
        inventoryRepository.deleteAll();
        unitOfMeasureRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();

        origin = branchRepository.save(new Branch("SUC-LG-O", "Origen", null));
        destination = branchRepository.save(new Branch("SUC-LG-D", "Destino", null));
        otherDestination = branchRepository.save(new Branch("SUC-LG-X", "Destino Alterno", null));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Gerente", "manager@test.local", hash, RoleCode.MANAGER, origin.getId()));
        userRepository.save(new User("Operador Origen", "operator.origin@test.local", hash, RoleCode.OPERATOR, origin.getId()));

        adminToken = login("admin@test.local");
        managerToken = login("manager@test.local");
        originOperatorToken = login("operator.origin@test.local");

        unUnit = unitOfMeasureRepository.save(new UnitOfMeasure("UN", "Unidad"));
    }

    // ---- Estimado vs. real ----

    @Test
    void reportComparesEstimatedAgainstActualDelivery() {
        String product = createProduct("SKU-LG-001");
        stockUp(product, origin.getId(), 100);
        deliver(product, destination, 10, FUTURE_DATE, 10);
        deliver(product, destination, 10, PAST_DATE, 10);

        LogisticsComplianceResponse.ComplianceMetrics metrics = report(adminToken, null, null).summary();

        assertThat(metrics.dispatched()).isEqualTo(2);
        assertThat(metrics.delivered()).isEqualTo(2);
        assertThat(metrics.onTime()).isEqualTo(1);
        assertThat(metrics.late()).isEqualTo(1);
        assertThat(metrics.complianceRate()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(metrics.averageDeliveryHours())
                .as("el tiempo real se deriva de dispatchedAt/receivedAt, no de un valor cargado a mano")
                .isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void deliveryDispatchedWithoutEstimatedDateIsNotCountedAsOnTime() {
        String product = createProduct("SKU-LG-002");
        stockUp(product, origin.getId(), 100);
        deliver(product, destination, 5, null, 5);

        LogisticsComplianceResponse.ComplianceMetrics metrics = report(adminToken, null, null).summary();

        assertThat(metrics.delivered()).isEqualTo(1);
        assertThat(metrics.notEvaluable()).isEqualTo(1);
        assertThat(metrics.onTime()).isZero();
        assertThat(metrics.late()).isZero();
        assertThat(metrics.complianceRate())
                .as("sin ninguna entrega evaluable no se inventa un 100%")
                .isNull();
    }

    // ---- Transferencia sin despacho ----

    @Test
    void transfersNeverDispatchedAreExcludedFromTheReport() {
        String product = createProduct("SKU-LG-003");
        stockUp(product, origin.getId(), 100);

        // Solicitada y aprobada, pero nunca despachada: no tiene tiempo de entrega que medir.
        TransferResponse pending = requestTransfer(product, destination, 10);
        approve(pending, 10);
        // Y una segunda apenas solicitada.
        requestTransfer(product, destination, 3);

        LogisticsComplianceResponse.ComplianceMetrics metrics = report(adminToken, null, null).summary();

        assertThat(metrics.dispatched()).isZero();
        assertThat(metrics.delivered()).isZero();
        assertThat(metrics.inTransit()).isZero();
        assertThat(report(adminToken, null, null).byRoute()).isEmpty();
    }

    @Test
    void inTransitTransfersAreVisibleAndFlaggedWhenOverdue() {
        String product = createProduct("SKU-LG-004");
        stockUp(product, origin.getId(), 100);
        dispatchOnly(product, destination, 10, PAST_DATE);
        dispatchOnly(product, destination, 10, FUTURE_DATE);

        LogisticsComplianceResponse.ComplianceMetrics metrics = report(adminToken, null, null).summary();

        assertThat(metrics.dispatched()).isEqualTo(2);
        assertThat(metrics.inTransit()).isEqualTo(2);
        assertThat(metrics.delivered()).isZero();
        assertThat(metrics.overdueInTransit())
                .as("solo la que ya pasó su fecha estimada cuenta como atrasada en curso")
                .isEqualTo(1);
    }

    // ---- Recepción parcial ----

    @Test
    void partialReceiptCountsAsDeliveredAndIsFlaggedAsShortage() {
        String product = createProduct("SKU-LG-005");
        stockUp(product, origin.getId(), 100);
        deliver(product, destination, 20, FUTURE_DATE, 15);

        LogisticsComplianceResponse.ComplianceMetrics metrics = report(adminToken, null, null).summary();

        assertThat(metrics.delivered()).isEqualTo(1);
        assertThat(metrics.withShortages()).isEqualTo(1);
        assertThat(metrics.onTime()).as("llegó incompleta pero a tiempo: son dos indicadores distintos").isEqualTo(1);
    }

    // ---- Filtros ----

    @Test
    void reportCanBeFilteredByRoute() {
        String product = createProduct("SKU-LG-006");
        stockUp(product, origin.getId(), 200);
        String routeToDestination = createRoute(origin, destination, "PRIORITY");
        createRoute(origin, otherDestination, "COST");

        deliver(product, destination, 10, FUTURE_DATE, 10);
        deliver(product, otherDestination, 10, PAST_DATE, 10);

        LogisticsComplianceResponse filtered = report(adminToken, null, routeToDestination);

        assertThat(filtered.summary().dispatched()).isEqualTo(1);
        assertThat(filtered.summary().onTime()).isEqualTo(1);
        assertThat(filtered.byRoute()).hasSize(1);
        assertThat(filtered.byRoute().get(0).routeId()).isEqualTo(routeToDestination);
        assertThat(filtered.byRoute().get(0).classification()).isEqualTo(RouteClassification.PRIORITY);

        assertThat(report(adminToken, null, null).byRoute())
                .as("sin filtro se reportan ambas rutas")
                .hasSize(2);
    }

    @Test
    void reportCanBeFilteredByBranch() {
        String product = createProduct("SKU-LG-007");
        stockUp(product, origin.getId(), 200);
        deliver(product, destination, 10, FUTURE_DATE, 10);
        deliver(product, otherDestination, 10, FUTURE_DATE, 10);

        LogisticsComplianceResponse filtered = report(adminToken, destination.getId(), null);

        assertThat(filtered.summary().dispatched()).isEqualTo(1);
        assertThat(filtered.byRoute()).hasSize(1);
        assertThat(filtered.byRoute().get(0).destinationBranchId()).isEqualTo(String.valueOf(destination.getId()));
        assertThat(filtered.appliedFilters().branchId()).isEqualTo(String.valueOf(destination.getId()));
    }

    @Test
    void reportOutsideTheDateRangeIsEmptyNotAnError() {
        String product = createProduct("SKU-LG-008");
        stockUp(product, origin.getId(), 100);
        deliver(product, destination, 10, FUTURE_DATE, 10);

        ResponseEntity<LogisticsComplianceResponse> response = getWithToken(
                "/api/v1/reports/logistics-compliance?dateFrom=2990-01-01T00:00:00Z", adminToken, LogisticsComplianceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().summary().dispatched()).isZero();
        assertThat(response.getBody().byRoute()).isEmpty();
    }

    /** RF-029: el estado en curso lo sirve el propio recurso de transferencias, sin duplicar la fuente de verdad. */
    @Test
    void inFlightStateIsQueriedThroughTheTransfersResourceByStatus() {
        String product = createProduct("SKU-LG-009");
        stockUp(product, origin.getId(), 100);
        dispatchOnly(product, destination, 10, FUTURE_DATE);
        deliver(product, destination, 5, FUTURE_DATE, 5);

        String inTransit = getWithToken("/api/v1/transfers?status=IN_TRANSIT", adminToken, String.class).getBody();
        assertThat(inTransit).contains("\"totalElements\":1").contains("\"status\":\"IN_TRANSIT\"");

        String complete = getWithToken("/api/v1/transfers?status=RECEIVED_COMPLETE", adminToken, String.class).getBody();
        assertThat(complete).contains("\"totalElements\":1");
    }

    // ---- Ruta materializada vs. derivada ----

    @Test
    void transferGetsItsRouteAutomaticallyAndIsReportedEvenIfClassifiedLater() {
        String product = createProduct("SKU-LG-010");
        stockUp(product, origin.getId(), 200);

        // Primera transferencia: el par aún no tiene ruta clasificada.
        deliver(product, destination, 10, FUTURE_DATE, 10);

        // Se clasifica la ruta DESPUÉS.
        String routeId = createRoute(origin, destination, "TIME");
        TransferResponse afterRoute = requestTransfer(product, destination, 5);
        assertThat(afterRoute.routeId())
                .as("la ruta se resuelve sola desde el par de sucursales, sin enviarla en el payload")
                .isEqualTo(routeId);

        LogisticsComplianceResponse report = report(adminToken, null, routeId);

        assertThat(report.summary().dispatched())
                .as("la transferencia creada antes de clasificar la ruta también cuenta: el reporte agrupa por par de sucursales")
                .isEqualTo(1);
        assertThat(report.byRoute()).hasSize(1);
        assertThat(report.byRoute().get(0).routeId()).isEqualTo(routeId);
    }

    // ---- Permisos ----

    @Test
    void operatorIsRestrictedToOwnBranchAndManagerIsNot() {
        String product = createProduct("SKU-LG-011");
        stockUp(product, origin.getId(), 100);
        deliver(product, destination, 10, FUTURE_DATE, 10);

        // El operador de origen consulta sin filtro: se le fuerza su propia sucursal.
        LogisticsComplianceResponse asOperator = report(originOperatorToken, null, null);
        assertThat(asOperator.appliedFilters().branchId()).isEqualTo(String.valueOf(origin.getId()));
        assertThat(asOperator.summary().dispatched()).isEqualTo(1);

        // Y no puede pedir el de otra sucursal.
        ResponseEntity<String> foreign = getWithToken(
                "/api/v1/reports/logistics-compliance?branchId=" + destination.getId(), originOperatorToken, String.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreign.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");

        // El gerente sí puede consultar cualquier sucursal (docs/API_DESIGN.md, sección 6).
        assertThat(report(managerToken, destination.getId(), null).summary().dispatched()).isEqualTo(1);
    }

    @Test
    void nonexistentRouteFilterReturns404() {
        assertThat(getWithToken("/api/v1/reports/logistics-compliance?routeId=999999", adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private LogisticsComplianceResponse report(String token, Long branchId, String routeId) {
        StringBuilder path = new StringBuilder("/api/v1/reports/logistics-compliance?");
        if (branchId != null) {
            path.append("branchId=").append(branchId).append('&');
        }
        if (routeId != null) {
            path.append("routeId=").append(routeId);
        }
        return getWithToken(path.toString(), token, LogisticsComplianceResponse.class).getBody();
    }

    private String createRoute(Branch from, Branch to, String classification) {
        return restTemplate.exchange("/api/v1/routes", HttpMethod.POST,
                new HttpEntity<>(Map.of("originBranchId", from.getId(), "destinationBranchId", to.getId(), "classification", classification),
                        authHeaders(adminToken)),
                RouteResponse.class).getBody().id();
    }

    private String createProduct(String sku) {
        return restTemplate.exchange("/api/v1/products", HttpMethod.POST,
                new HttpEntity<>(Map.of("sku", sku, "name", "Producto " + sku, "baseUnitOfMeasureId", unUnit.getId(), "minimumStock", 0, "unitPrice", 10),
                        authHeaders(originOperatorToken)),
                ProductResponse.class).getBody().id();
    }

    private void stockUp(String productId, Long branchId, int quantity) {
        restTemplate.exchange("/api/v1/inventory/adjustments", HttpMethod.POST,
                new HttpEntity<>(Map.of("branchId", branchId, "productId", Long.valueOf(productId),
                        "direction", "INGRESO", "quantity", quantity, "notes", "Carga inicial de prueba"),
                        authHeaders(adminToken)),
                Object.class);
    }

    private TransferResponse requestTransfer(String productId, Branch to, int quantity) {
        HttpHeaders headers = authHeaders(adminToken);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(Map.of("originBranchId", origin.getId(), "destinationBranchId", to.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityRequested", quantity))), headers),
                TransferResponse.class).getBody();
    }

    private void approve(TransferResponse transfer, int quantity) {
        restTemplate.exchange("/api/v1/transfers/" + transfer.id() + "/approve", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", List.of(
                        Map.of("transferItemId", Long.valueOf(transfer.items().get(0).id()), "quantityApproved", quantity))),
                        authHeaders(adminToken)),
                TransferResponse.class);
    }

    /** Deja la transferencia en tránsito; {@code estimatedArrivalDate} nulo se omite del payload. */
    private TransferResponse dispatchOnly(String productId, Branch to, int quantity, String estimatedArrivalDate) {
        TransferResponse transfer = requestTransfer(productId, to, quantity);
        approve(transfer, quantity);
        Map<String, Object> body = estimatedArrivalDate == null
                ? Map.of("carrierName", "Transportes XYZ",
                        "items", List.of(Map.of("transferItemId", Long.valueOf(transfer.items().get(0).id()), "quantityShipped", quantity)))
                : Map.of("carrierName", "Transportes XYZ", "estimatedArrivalDate", estimatedArrivalDate,
                        "items", List.of(Map.of("transferItemId", Long.valueOf(transfer.items().get(0).id()), "quantityShipped", quantity)));
        return restTemplate.exchange("/api/v1/transfers/" + transfer.id() + "/dispatch", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(adminToken)), TransferResponse.class).getBody();
    }

    /** Ciclo completo hasta la recepción; {@code received < shipped} produce una recepción parcial. */
    private TransferResponse deliver(String productId, Branch to, int shipped, String estimatedArrivalDate, int received) {
        TransferResponse transfer = dispatchOnly(productId, to, shipped, estimatedArrivalDate);
        TransferResponse result = restTemplate.exchange("/api/v1/transfers/" + transfer.id() + "/receive", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", List.of(
                        Map.of("transferItemId", Long.valueOf(transfer.items().get(0).id()), "quantityReceived", received))),
                        authHeaders(adminToken)),
                TransferResponse.class).getBody();
        assertThat(result.status()).isIn(TransferStatus.RECEIVED_COMPLETE, TransferStatus.RECEIVED_PARTIAL);
        return result;
    }

    private String login(String email) {
        var body = new com.inventario.multisucursal.auth.LoginRequest(email, SEED_PASSWORD);
        return restTemplate.postForEntity("/api/v1/auth/login", body, com.inventario.multisucursal.auth.LoginResponse.class)
                .getBody().accessToken();
    }

    private <T> ResponseEntity<T> getWithToken(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), responseType);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
