package com.inventario.multisucursal.purchases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.products.ProductResponse;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.suppliers.Supplier;
import com.inventario.multisucursal.suppliers.SupplierRepository;
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
 * Ciclo de vida de la orden de compra (RF-012, RF-013), sin recepción — ver
 * {@link PurchaseReceiptApiTest} para el flujo B completo. Cubre: compra
 * válida, descuentos, datos inválidos, permisos y sucursal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PurchaseOrderApiTest {

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
    private SupplierRepository supplierRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Branch branchA;
    private Branch branchB;
    private UnitOfMeasure unUnit;
    private Supplier supplier;
    private String adminToken;
    private String managerAToken;
    private String operatorAToken;
    private String operatorBToken;

    @BeforeEach
    void setUp() {
        purchaseOrderItemRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        supplierRepository.deleteAll();
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
        supplier = supplierRepository.save(new Supplier("Proveedor Uno", "TAX-001", "Contacto", "555-0001", "prov@test.local"));
    }

    // ---- Compra válida ----

    @Test
    void validPurchaseOrderIsCreatedWithLineTotal() {
        String productId = createProduct("SKU-PO-001");

        ResponseEntity<PurchaseOrderResponse> response = createOrder(
                Map.of(
                        "supplierId", supplier.getId(),
                        "branchId", branchA.getId(),
                        "paymentTerm", "30 días",
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 10, "unitPrice", 20))),
                operatorAToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo(PurchaseOrderStatus.CREATED);
        assertThat(response.getBody().orderNumber()).startsWith("OC-");
        assertThat(response.getBody().items()).hasSize(1);
        assertThat(response.getBody().items().get(0).lineTotal()).isEqualByComparingTo(new BigDecimal("200.0000"));
        assertThat(response.getBody().items().get(0).quantityReceived()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- Descuentos ----

    @Test
    void discountReducesLineTotal() {
        String productId = createProduct("SKU-PO-002");

        ResponseEntity<PurchaseOrderResponse> response = createOrder(
                Map.of(
                        "supplierId", supplier.getId(),
                        "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 10, "unitPrice", 20, "discountPercentage", 25))),
                operatorAToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // 10 * 20 = 200; 25% descuento = 150
        assertThat(response.getBody().items().get(0).lineTotal()).isEqualByComparingTo(new BigDecimal("150.0000"));
    }

    @Test
    void fullDiscountLeavesLineTotalAtZero() {
        String productId = createProduct("SKU-PO-003");

        ResponseEntity<PurchaseOrderResponse> response = createOrder(
                Map.of(
                        "supplierId", supplier.getId(),
                        "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10, "discountPercentage", 100))),
                operatorAToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().items().get(0).lineTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void discountAbove100IsRejected() {
        String productId = createProduct("SKU-PO-004");

        ResponseEntity<String> response = createOrder(
                Map.of(
                        "supplierId", supplier.getId(),
                        "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10, "discountPercentage", 150))),
                operatorAToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- Datos inválidos ----

    @Test
    void nonPositiveUnitPriceIsRejectedAs400() {
        String productId = createProduct("SKU-PO-005");

        ResponseEntity<String> response = createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 0))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nonPositiveQuantityOrderedIsRejectedAs422() {
        String productId = createProduct("SKU-PO-006");

        ResponseEntity<String> response = createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 0, "unitPrice", 10))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_INVALIDA\"");
    }

    @Test
    void duplicateProductInSameOrderIsRejected() {
        String productId = createProduct("SKU-PO-007");

        ResponseEntity<String> response = createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchA.getId(),
                        "items", List.of(
                                Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10),
                                Map.of("productId", Long.valueOf(productId), "quantityOrdered", 3, "unitPrice", 12))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"PRODUCTO_DUPLICADO_EN_ORDEN\"");
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        String productId = createProduct("SKU-PO-008");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/purchase-orders", HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("supplierId", supplier.getId(), "branchId", branchA.getId(),
                                "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10))),
                        authHeaders(operatorAToken)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"IDEMPOTENCY_KEY_REQUERIDO\"");
    }

    @Test
    void nonexistentSupplierReturns404() {
        String productId = createProduct("SKU-PO-009");

        ResponseEntity<String> response = createOrder(
                Map.of("supplierId", 999_999, "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"PROVEEDOR_NO_ENCONTRADO\"");
    }

    @Test
    void nonexistentProductInLineReturns404() {
        ResponseEntity<String> response = createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", 999_999, "quantityOrdered", 5, "unitPrice", 10))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"PRODUCTO_NO_ENCONTRADO\"");
    }

    // ---- Permisos y sucursal ----

    @Test
    void managerCanCreatePurchaseOrder() {
        // Ampliación de permisos: MANAGER queda habilitado igual que OPERATOR/ADMIN.
        String productId = createProduct("SKU-PO-010");

        ResponseEntity<PurchaseOrderResponse> response = createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10))),
                managerAToken, PurchaseOrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void operatorCannotCreateOrderForAnotherBranch() {
        String productId = createProduct("SKU-PO-011");

        ResponseEntity<String> response = createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchB.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");
    }

    @Test
    void adminCanCreateOrderForAnyBranch() {
        String productId = createProduct("SKU-PO-012");

        ResponseEntity<PurchaseOrderResponse> response = createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchB.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10))),
                adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void operatorCannotReadOrderFromAnotherBranch() {
        String productId = createProduct("SKU-PO-013");
        String orderId = createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchB.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10))),
                adminToken).getBody().id();

        ResponseEntity<String> response = getWithToken("/api/v1/purchase-orders/" + orderId, operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");
    }

    @Test
    void listOnlyReturnsOwnBranchOrdersForNonAdmin() {
        String productA = createProduct("SKU-PO-014A");
        String productB = createProduct("SKU-PO-014B");
        createOrder(Map.of("supplierId", supplier.getId(), "branchId", branchA.getId(),
                "items", List.of(Map.of("productId", Long.valueOf(productA), "quantityOrdered", 1, "unitPrice", 10))), operatorAToken);
        createOrder(Map.of("supplierId", supplier.getId(), "branchId", branchB.getId(),
                "items", List.of(Map.of("productId", Long.valueOf(productB), "quantityOrdered", 1, "unitPrice", 10))), operatorBToken);

        ResponseEntity<String> response = getWithToken("/api/v1/purchase-orders?branchId=" + branchB.getId(), operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"branchId\":\"" + branchA.getId() + "\"");
        assertThat(response.getBody()).doesNotContain("\"branchId\":\"" + branchB.getId() + "\"");
    }

    // ---- Estados de la orden ----

    @Test
    void createdOrderCanBeCancelled() {
        String productId = createProduct("SKU-PO-015");
        String orderId = createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10))),
                operatorAToken).getBody().id();

        ResponseEntity<PurchaseOrderResponse> response = restTemplate.exchange(
                "/api/v1/purchase-orders/" + orderId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(null, authHeaders(operatorAToken)), PurchaseOrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
    }

    @Test
    void nonCreatedOrderCannotBeCancelled() {
        String productId = createProduct("SKU-PO-016");
        Long orderId = Long.valueOf(createOrder(
                Map.of("supplierId", supplier.getId(), "branchId", branchA.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityOrdered", 5, "unitPrice", 10))),
                operatorAToken).getBody().id());

        PurchaseOrder order = purchaseOrderRepository.findById(orderId).orElseThrow();
        order.updateStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        purchaseOrderRepository.saveAndFlush(order);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/purchase-orders/" + orderId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(null, authHeaders(operatorAToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"TRANSICION_INVALIDA\"");
    }

    @Test
    void operationsOnNonexistentOrderReturn404() {
        assertThat(getWithToken("/api/v1/purchase-orders/999999", adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private String createProduct(String sku) {
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/v1/products", HttpMethod.POST,
                new HttpEntity<>(Map.of("sku", sku, "name", "Producto " + sku, "baseUnitOfMeasureId", unUnit.getId(), "minimumStock", 0, "unitPrice", 10), authHeaders(operatorAToken)),
                ProductResponse.class);
        return response.getBody().id();
    }

    private ResponseEntity<PurchaseOrderResponse> createOrder(Object body, String token) {
        return createOrder(body, token, PurchaseOrderResponse.class);
    }

    private <T> ResponseEntity<T> createOrder(Object body, String token, Class<T> responseType) {
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return restTemplate.exchange("/api/v1/purchase-orders", HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
    }

    private String login(String email) {
        var body = new com.inventario.multisucursal.auth.LoginRequest(email, SEED_PASSWORD);
        ResponseEntity<com.inventario.multisucursal.auth.LoginResponse> response =
                restTemplate.postForEntity("/api/v1/auth/login", body, com.inventario.multisucursal.auth.LoginResponse.class);
        return response.getBody().accessToken();
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
