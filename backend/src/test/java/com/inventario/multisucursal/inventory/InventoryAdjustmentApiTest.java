package com.inventario.multisucursal.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.products.ProductResponse;
import com.inventario.multisucursal.products.ProductUnitResponse;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC de inventario/movimientos (docs/BUSINESS_RULES.md; docs/CRITICAL_FLOWS.md,
 * flujo G). Cubre exactamente lo pedido: entrada válida, retiro válido,
 * retiro sin stock, ajuste, movimiento auditable completo, consulta por
 * sucursal, permisos. La concurrencia (dos retiros simultáneos) y el
 * rollback ante fallo se cubren en clases separadas
 * ({@link InventoryConcurrencyTest}, {@link InventoryAdjustmentRollbackTest})
 * porque requieren un montaje distinto (hilos reales / mocks).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class InventoryAdjustmentApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryMovementRepository movementRepository;

    private Branch branchA;
    private Branch branchB;
    private UnitOfMeasure unUnit;
    private String adminToken;
    private String managerAToken;
    private String operatorAToken;
    private String operatorBToken;

    @BeforeEach
    void setUp() {
        movementRepository.deleteAll();
        inventoryRepository.deleteAll();
        unitOfMeasureRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branchA = branchRepository.save(new Branch("SUC-A", "Sucursal A", "Calle 1"));
        branchB = branchRepository.save(new Branch("SUC-B", "Sucursal B", "Calle 2"));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Gerente A", "manager.a@test.local", hash, RoleCode.MANAGER, branchA.getId()));
        userRepository.save(new User("Operador A", "operator.a@test.local", hash, RoleCode.OPERATOR, branchA.getId()));
        userRepository.save(new User("Operador B", "operator.b@test.local", hash, RoleCode.OPERATOR, branchB.getId()));

        adminToken = login("admin@test.local");
        managerAToken = login("manager.a@test.local");
        operatorAToken = login("operator.a@test.local");
        operatorBToken = login("operator.b@test.local");

        unUnit = unitOfMeasureRepository.save(new UnitOfMeasure("UN", "Unidad"));
    }

    // ---- Entrada válida ----

    @Test
    void validEntryIncreasesStockAndCreatesMovement() {
        String productId = createProduct("SKU-ENT-001");

        ResponseEntity<InventoryMovementResponse> response = adjust(
                Map.of(
                        "branchId", branchA.getId(),
                        "productId", Long.valueOf(productId),
                        "direction", "INGRESO",
                        "quantity", 10,
                        "notes", "Conteo físico inicial"),
                operatorAToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().direction()).isEqualTo(MovementDirection.INGRESO);
        assertThat(response.getBody().reason()).isEqualTo(MovementReason.AJUSTE_INGRESO);
        assertThat(response.getBody().quantity()).isEqualByComparingTo(BigDecimal.TEN);

        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(BigDecimal.TEN);
    }

    // ---- Retiro válido ----

    @Test
    void validWithdrawalDecreasesStock() {
        String productId = createProduct("SKU-RET-001");
        adjust(Map.of("branchId", branchA.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 10, "notes", "Ingreso inicial"), operatorAToken);

        ResponseEntity<InventoryMovementResponse> response = adjust(
                Map.of("branchId", branchA.getId(), "productId", Long.valueOf(productId), "direction", "RETIRO", "quantity", 4, "notes", "Merma detectada"),
                operatorAToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().direction()).isEqualTo(MovementDirection.RETIRO);
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("6"));
    }

    // ---- Retiro sin stock ----

    @Test
    void withdrawalWithoutSufficientStockIsRejected() {
        String productId = createProduct("SKU-RET-002");

        ResponseEntity<String> response = adjust(
                Map.of("branchId", branchA.getId(), "productId", Long.valueOf(productId), "direction", "RETIRO", "quantity", 5, "notes", "Intento sin stock"),
                operatorAToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"STOCK_INSUFICIENTE\"");
        assertThat(movementRepository.findAll()).isEmpty();
    }

    // ---- Ajuste (motivo explícito, conversión de unidad) ----

    @Test
    void explicitAdjustmentReasonIsRespectedWhenCompatibleWithDirection() {
        String productId = createProduct("SKU-AJU-001");

        ResponseEntity<InventoryMovementResponse> response = adjust(
                Map.of("branchId", branchA.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "reason", "DEVOLUCION", "quantity", 3, "notes", "Devolución de cliente"),
                operatorAToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().reason()).isEqualTo(MovementReason.DEVOLUCION);
    }

    @Test
    void reasonIncompatibleWithDirectionIsRejected() {
        String productId = createProduct("SKU-AJU-002");

        ResponseEntity<String> response = adjust(
                Map.of("branchId", branchA.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "reason", "MERMA", "quantity", 3, "notes", "Motivo incoherente"),
                operatorAToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"MOTIVO_INCOMPATIBLE_CON_DIRECCION\"");
    }

    @Test
    void adjustmentConvertsQuantityFromAlternateUnitToBaseUnit() {
        String productId = createProduct("SKU-AJU-003");
        UnitOfMeasure box = unitOfMeasureRepository.save(new UnitOfMeasure("CJ", "Caja"));
        post("/api/v1/products/" + productId + "/units",
                Map.of("unitOfMeasureId", box.getId(), "conversionFactorToBase", 12), operatorAToken, ProductUnitResponse.class);

        adjust(Map.of(
                "branchId", branchA.getId(), "productId", Long.valueOf(productId), "unitOfMeasureId", box.getId(),
                "direction", "INGRESO", "quantity", 2, "notes", "2 cajas de 12"), operatorAToken);

        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("24"));
    }

    @Test
    void nonPositiveQuantityIsRejected() {
        String productId = createProduct("SKU-AJU-004");

        ResponseEntity<String> response = adjust(
                Map.of("branchId", branchA.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 0, "notes", "Cantidad inválida"),
                operatorAToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_INVALIDA\"");
    }

    @Test
    void missingNotesIsRejected() {
        String productId = createProduct("SKU-AJU-005");

        ResponseEntity<String> response = adjust(
                Map.of("branchId", branchA.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 1),
                operatorAToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"NOTES_REQUERIDO\"");
    }

    // ---- Movimiento auditable completo ----

    @Test
    void movementRecordsFullAuditTrail() {
        String productId = createProduct("SKU-AUD-001");
        Long operatorAId = userRepository.findByEmail("operator.a@test.local").orElseThrow().getId();

        adjust(Map.of("branchId", branchA.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 7, "notes", "Ingreso auditado"), operatorAToken);

        ResponseEntity<String> listResponse = getWithToken(
                "/api/v1/inventory-movements?branchId=" + branchA.getId() + "&productId=" + productId, adminToken, String.class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody())
                .contains("\"responsibleUserId\":\"" + operatorAId + "\"")
                .contains("\"reason\":\"AJUSTE_INGRESO\"")
                .contains("\"direction\":\"INGRESO\"")
                .contains("\"notes\":\"Ingreso auditado\"")
                .contains("\"occurredAt\"")
                .contains("\"unitOfMeasureId\":\"" + unUnit.getId() + "\"");
    }

    // ---- Consulta por sucursal ----

    @Test
    void inventoryCanBeQueriedByBranchFromAnyRole() {
        String productId = createProduct("SKU-CON-001");
        adjust(Map.of("branchId", branchB.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 15, "notes", "Stock inicial sucursal B"), adminToken);

        ResponseEntity<String> response = getWithToken("/api/v1/inventory?branchId=" + branchB.getId(), managerAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"branchId\":\"" + branchB.getId() + "\"");
        assertThat(response.getBody()).doesNotContain("\"branchId\":\"" + branchA.getId() + "\"");
    }

    // ---- Permisos ----

    @Test
    void managerCannotCreateAdjustment() {
        String productId = createProduct("SKU-PER-001");

        ResponseEntity<String> response = adjust(
                Map.of("branchId", branchA.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 1, "notes", "Intento de gerente"),
                managerAToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"ROL_NO_AUTORIZADO\"");
    }

    @Test
    void operatorCannotAdjustAnotherBranch() {
        String productId = createProduct("SKU-PER-002");

        ResponseEntity<String> response = adjust(
                Map.of("branchId", branchB.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 1, "notes", "Intento de sucursal ajena"),
                operatorAToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");
    }

    @Test
    void adminCanAdjustAnyBranch() {
        String productId = createProduct("SKU-PER-003");

        ResponseEntity<InventoryMovementResponse> response = adjust(
                Map.of("branchId", branchB.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 1, "notes", "Ajuste de admin"),
                adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void operatorCanAdjustOwnBranch() {
        String productId = createProduct("SKU-PER-004");

        ResponseEntity<InventoryMovementResponse> response = adjust(
                Map.of("branchId", branchB.getId(), "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 1, "notes", "Ajuste propio"),
                operatorBToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- Recurso inexistente / estado inválido ----

    @Test
    void adjustmentOnNonexistentProductReturns404() {
        ResponseEntity<String> response = adjust(
                Map.of("branchId", branchA.getId(), "productId", 999_999, "direction", "INGRESO", "quantity", 1, "notes", "Producto inexistente"),
                operatorAToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"PRODUCTO_NO_ENCONTRADO\"");
    }

    @Test
    void adjustmentOnNonexistentBranchReturns404() {
        String productId = createProduct("SKU-404-001");

        ResponseEntity<String> response = adjust(
                Map.of("branchId", 999_999, "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", 1, "notes", "Sucursal inexistente"),
                adminToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_ENCONTRADA\"");
    }

    // ---- helpers ----

    private String createProduct(String sku) {
        ResponseEntity<ProductResponse> response = post(
                "/api/v1/products",
                Map.of("sku", sku, "name", "Producto " + sku, "baseUnitOfMeasureId", unUnit.getId(), "minimumStock", 0, "unitPrice", 10),
                operatorAToken,
                ProductResponse.class);
        return response.getBody().id();
    }

    private BigDecimal stockOf(String productId, Long branchId) {
        return inventoryRepository.findByProductIdAndBranchId(Long.valueOf(productId), branchId)
                .orElseThrow(() -> new AssertionError("No existe inventario para producto=" + productId + " sucursal=" + branchId))
                .getQuantityOnHand();
    }

    private ResponseEntity<InventoryMovementResponse> adjust(Map<String, Object> body, String token) {
        return adjust(body, token, InventoryMovementResponse.class);
    }

    private <T> ResponseEntity<T> adjust(Map<String, Object> body, String token, Class<T> responseType) {
        return post("/api/v1/inventory/adjustments", body, token, responseType);
    }

    private String login(String email) {
        var body = new com.inventario.multisucursal.auth.LoginRequest(email, SEED_PASSWORD);
        ResponseEntity<com.inventario.multisucursal.auth.LoginResponse> response =
                restTemplate.postForEntity("/api/v1/auth/login", body, com.inventario.multisucursal.auth.LoginResponse.class);
        return response.getBody().accessToken();
    }

    private <T> ResponseEntity<T> post(String path, Object body, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), responseType);
    }

    private <T> ResponseEntity<T> getWithToken(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), responseType);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Autowired
    private PasswordEncoder passwordEncoder;
}
