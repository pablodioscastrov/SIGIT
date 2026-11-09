package com.inventario.multisucursal.inventory;

import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.ProductUnit;
import com.inventario.multisucursal.products.ProductUnitRepository;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alertas de stock mínimo (BR-010, UC-16, RF-010/RF-036 — funcionalidad
 * adicional elegida). Se siembra vía {@link InventoryMovementService} real
 * (no escribiendo `Inventory`/`StockAlert` a mano): así cada prueba ejercita
 * exactamente el mismo camino que un ajuste/venta/compra/transferencia real
 * usaría para disparar o resolver una alerta (ver
 * docs/adr/ADR-015-alertas-de-stock-minimo.md).
 *
 * <p>Cubre lo pedido explícitamente: cruce de umbral, actualización repetida
 * sin duplicar, recuperación de stock, una nueva caída después de resolver
 * (no reabre la anterior), permisos/alcance por sucursal (lectura abierta,
 * igual que `inventory`, RF-003). El aislamiento ante un fallo de
 * notificación se cubre aparte en {@link StockAlertNotificationFailureTest},
 * que necesita reemplazar el repositorio real con un doble que falle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StockAlertApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductUnitRepository productUnitRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryMovementService inventoryMovementService;

    @Autowired
    private StockAlertRepository stockAlertRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Branch branchA;
    private Branch branchB;
    private User adminUser;
    private UnitOfMeasure unit;
    private String adminToken;
    private String operatorBToken;

    @BeforeEach
    void setUp() {
        stockAlertRepository.deleteAll();
        productUnitRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
        unitOfMeasureRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branchA = branchRepository.save(new Branch("SUC-A", "Sucursal A"));
        branchB = branchRepository.save(new Branch("SUC-B", "Sucursal B"));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        adminUser = userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Operador B", "operator.b@test.local", hash, RoleCode.OPERATOR, branchB.getId()));

        adminToken = login("admin@test.local");
        operatorBToken = login("operator.b@test.local");

        unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN", "Unidad"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- Cruce de umbral ----

    @Test
    void withdrawalThatLeavesStockExactlyAtMinimumTriggersAlert() {
        Product product = seedProduct("SKU-A1");
        seedInventoryWithMinimum(product.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("10"));

        withdraw(product.getId(), branchA.getId(), new BigDecimal("10")); // deja el stock en 10 == mínimo

        List<StockAlertResponse> alerts = listAlerts(adminToken, branchA.getId(), "ACTIVE");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).productId()).isEqualTo(String.valueOf(product.getId()));
        assertThat(alerts.get(0).quantityOnHand()).isEqualByComparingTo("10");
        assertThat(alerts.get(0).resolvedAt()).isNull();
    }

    @Test
    void withdrawalThatStaysAboveMinimumDoesNotTriggerAlert() {
        Product product = seedProduct("SKU-A2");
        seedInventoryWithMinimum(product.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("10"));

        withdraw(product.getId(), branchA.getId(), new BigDecimal("5")); // deja el stock en 15 > mínimo

        assertThat(listAlerts(adminToken, branchA.getId(), null)).isEmpty();
    }

    // ---- Actualización repetida sin duplicar ----

    @Test
    void repeatedWithdrawalsBelowMinimumDoNotDuplicateTheActiveAlert() {
        Product product = seedProduct("SKU-A3");
        seedInventoryWithMinimum(product.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("10"));

        withdraw(product.getId(), branchA.getId(), new BigDecimal("11")); // 9 <= 10: dispara
        withdraw(product.getId(), branchA.getId(), new BigDecimal("2")); // 7 <= 10: sigue por debajo

        List<StockAlertResponse> active = listAlerts(adminToken, branchA.getId(), "ACTIVE");
        assertThat(active).hasSize(1);
        // La fila persistida es la única (comprobación directa, no solo lo que expone la API).
        assertThat(stockAlertRepository.findAll()).hasSize(1);
    }

    // ---- Recuperación de stock ----

    @Test
    void restockAboveMinimumResolvesTheActiveAlert() {
        Product product = seedProduct("SKU-A4");
        seedInventoryWithMinimum(product.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("10"));
        withdraw(product.getId(), branchA.getId(), new BigDecimal("15")); // 5 <= 10: dispara

        deposit(product.getId(), branchA.getId(), new BigDecimal("10")); // 15 > 10: resuelve

        assertThat(listAlerts(adminToken, branchA.getId(), "ACTIVE")).isEmpty();
        List<StockAlertResponse> resolved = listAlerts(adminToken, branchA.getId(), "RESOLVED");
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).resolvedAt()).isNotNull();
    }

    @Test
    void restockThatDoesNotReachMinimumDoesNotResolve() {
        Product product = seedProduct("SKU-A5");
        seedInventoryWithMinimum(product.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("10"));
        withdraw(product.getId(), branchA.getId(), new BigDecimal("15")); // 5 <= 10: dispara

        deposit(product.getId(), branchA.getId(), new BigDecimal("2")); // 7, sigue <= 10

        assertThat(listAlerts(adminToken, branchA.getId(), "ACTIVE")).hasSize(1);
    }

    @Test
    void restockingWithNoActiveAlertIsANoOp() {
        Product product = seedProduct("SKU-A6");
        seedInventoryWithMinimum(product.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("10"));

        deposit(product.getId(), branchA.getId(), new BigDecimal("5")); // nunca bajó del mínimo

        assertThat(stockAlertRepository.findAll()).isEmpty();
    }

    // ---- Una nueva caída tras resolver crea una alerta nueva, no reabre ----

    @Test
    void aNewDropAfterResolutionCreatesANewAlertInsteadOfReopening() {
        Product product = seedProduct("SKU-A7");
        seedInventoryWithMinimum(product.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("10"));
        withdraw(product.getId(), branchA.getId(), new BigDecimal("15")); // dispara (5)
        deposit(product.getId(), branchA.getId(), new BigDecimal("10")); // resuelve (15)

        withdraw(product.getId(), branchA.getId(), new BigDecimal("10")); // dispara de nuevo (5)

        assertThat(stockAlertRepository.findAll()).hasSize(2);
        assertThat(listAlerts(adminToken, branchA.getId(), "ACTIVE")).hasSize(1);
        assertThat(listAlerts(adminToken, branchA.getId(), "RESOLVED")).hasSize(1);
    }

    // ---- Permisos y alcance por sucursal ----

    @Test
    void anyAuthenticatedRoleCanReadAlertsFromAnyBranch() {
        Product product = seedProduct("SKU-A8");
        seedInventoryWithMinimum(product.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("10"));
        withdraw(product.getId(), branchA.getId(), new BigDecimal("15"));

        // operatorBToken pertenece a branchB, distinta de donde ocurrió la alerta — RF-003.
        assertThat(listAlerts(operatorBToken, branchA.getId(), null)).hasSize(1);
    }

    @Test
    void branchFilterScopesTheResults() {
        Product productA = seedProduct("SKU-A9");
        Product productB = seedProduct("SKU-B9");
        seedInventoryWithMinimum(productA.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("10"));
        seedInventoryWithMinimum(productB.getId(), branchB.getId(), new BigDecimal("20"), new BigDecimal("10"));
        withdraw(productA.getId(), branchA.getId(), new BigDecimal("15"));
        withdraw(productB.getId(), branchB.getId(), new BigDecimal("15"));

        assertThat(listAlerts(adminToken, branchA.getId(), null)).hasSize(1);
        assertThat(listAlerts(adminToken, branchB.getId(), null)).hasSize(1);
    }

    @Test
    void unauthenticatedRequestIsRejected() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/stock-alerts", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- helpers ----

    private Product seedProduct(String sku) {
        Product product = productRepository.save(new Product(sku, "Producto " + sku, null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        return product;
    }

    /**
     * No existe endpoint de escritura para {@code minimum_stock} (limitación
     * conocida, docs/STATUS.md): se fija directo vía {@link JdbcTemplate},
     * mismo recurso ya usado por {@code DashboardApiTest}.
     */
    private void seedInventoryWithMinimum(Long productId, Long branchId, BigDecimal quantityOnHand, BigDecimal minimumStock) {
        authenticateAs(adminUser.getId(), RoleCode.ADMIN, null);
        inventoryMovementService.createAdjustment(
                new InventoryAdjustmentRequest(branchId, productId, null, MovementDirection.INGRESO, null, quantityOnHand, "Siembra de prueba"),
                adminUser.getId());
        jdbcTemplate.update("UPDATE inventory SET minimum_stock = ? WHERE product_id = ? AND branch_id = ?", minimumStock, productId, branchId);
    }

    private void withdraw(Long productId, Long branchId, BigDecimal quantity) {
        authenticateAs(adminUser.getId(), RoleCode.ADMIN, null);
        inventoryMovementService.createAdjustment(
                new InventoryAdjustmentRequest(branchId, productId, null, MovementDirection.RETIRO, null, quantity, "Retiro de prueba"),
                adminUser.getId());
    }

    private void deposit(Long productId, Long branchId, BigDecimal quantity) {
        authenticateAs(adminUser.getId(), RoleCode.ADMIN, null);
        inventoryMovementService.createAdjustment(
                new InventoryAdjustmentRequest(branchId, productId, null, MovementDirection.INGRESO, null, quantity, "Ingreso de prueba"),
                adminUser.getId());
    }

    private void authenticateAs(Long userId, RoleCode role, Long branchId) {
        var principal = new com.inventario.multisucursal.auth.AuthenticatedUser(userId, "Test", "test@test.local", role, branchId);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private List<StockAlertResponse> listAlerts(String token, Long branchId, String status) {
        String url = "/api/v1/stock-alerts?branchId=" + branchId + (status != null ? "&status=" + status : "");
        ResponseEntity<StockAlertPageResponse> response =
                restTemplate.exchange(url, HttpMethod.GET, authorized(token), StockAlertPageResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().content();
    }

    private HttpEntity<Void> authorized(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private String login(String email) {
        var body = new com.inventario.multisucursal.auth.LoginRequest(email, SEED_PASSWORD);
        ResponseEntity<com.inventario.multisucursal.auth.LoginResponse> response =
                restTemplate.postForEntity("/api/v1/auth/login", body, com.inventario.multisucursal.auth.LoginResponse.class);
        return response.getBody().accessToken();
    }

    /** Forma mínima para deserializar `PageResponse&lt;StockAlertResponse&gt;` en la prueba. */
    private record StockAlertPageResponse(List<StockAlertResponse> content) {
    }
}
