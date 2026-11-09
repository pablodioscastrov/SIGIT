package com.inventario.multisucursal.purchases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryRepository;
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
 * Flujo B completo (docs/CRITICAL_FLOWS.md): recepción, costo promedio
 * ponderado (con stock previo y con stock cero), idempotencia (doble
 * recepción/reintento), datos inválidos, permisos y sucursal. El rollback
 * ante fallo se cubre en {@link PurchaseReceiptRollbackTest} (requiere un
 * mock para forzar el fallo a mitad de transacción).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PurchaseReceiptApiTest {

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
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

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
        inventoryRepository.deleteAll();
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
        supplier = supplierRepository.save(new Supplier("Proveedor Uno", "TAX-001", null, null, null));
    }

    // ---- Recepción ----

    @Test
    void fullReceiptMarksOrderReceivedAndIncrementsStock() {
        String productId = createProduct("SKU-REC-001");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 10, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<PurchaseReceiptResponse> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 10, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PurchaseOrderStatus.RECEIVED);

        // No basta con el status devuelto en la respuesta: se relee la orden en una
        // petición nueva para confirmar que el estado quedó realmente persistido.
        ResponseEntity<PurchaseOrderResponse> reread = getWithToken("/api/v1/purchase-orders/" + order.id(), operatorAToken, PurchaseOrderResponse.class);
        assertThat(reread.getBody().status()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(response.getBody().items().get(0).quantityReceived()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(response.getBody().items().get(0).pending()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getBody().inventoryUpdates().get(0).quantityOnHand()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void partialReceiptMarksOrderPartiallyReceived() {
        String productId = createProduct("SKU-REC-002");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 10, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<PurchaseReceiptResponse> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 4, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(response.getBody().items().get(0).pending()).isEqualByComparingTo(new BigDecimal("6"));
    }

    @Test
    void secondPartialReceiptCompletesTheOrder() {
        String productId = createProduct("SKU-REC-003");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 10, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        receive(order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 4, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        ResponseEntity<PurchaseReceiptResponse> second = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 6, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        assertThat(second.getBody().status()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void movementIsAuditableAndLinkedToPurchaseOrderItem() {
        String productId = createProduct("SKU-REC-004");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 5, 20, null, operatorAToken);
        String itemId = order.items().get(0).id();

        receive(order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 5, "unitPrice", 20)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        ResponseEntity<String> movements = getWithToken(
                "/api/v1/inventory-movements?branchId=" + branchA.getId() + "&productId=" + productId, adminToken, String.class);

        assertThat(movements.getBody())
                .contains("\"reason\":\"COMPRA\"")
                .contains("\"direction\":\"INGRESO\"")
                .contains("\"type\":\"PURCHASE_ORDER\"")
                .contains("\"id\":\"" + itemId + "\"");
    }

    // ---- Costo promedio ponderado ----

    @Test
    void averageCostWithZeroPreviousStockEqualsReceivedPrice() {
        String productId = createProduct("SKU-COST-001");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 20, 8, null, operatorAToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<PurchaseReceiptResponse> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 20, "unitPrice", 8)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        assertThat(response.getBody().inventoryUpdates().get(0).averageUnitCost()).isEqualByComparingTo(new BigDecimal("8"));
    }

    @Test
    void averageCostWithPreviousStockIsWeighted() {
        String productId = createProduct("SKU-COST-002");

        // Primera orden: 100 u. a $10 -> costo promedio = 10.
        PurchaseOrderResponse firstOrder = createOrder(branchA.getId(), productId, 100, 10, null, operatorAToken);
        receive(firstOrder.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(firstOrder.items().get(0).id()), "quantityReceived", 100, "unitPrice", 10)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        // Segunda orden: 50 u. a $16 -> costo promedio = (100*10 + 50*16) / 150 = 12.
        PurchaseOrderResponse secondOrder = createOrder(branchA.getId(), productId, 50, 16, null, operatorAToken);
        ResponseEntity<PurchaseReceiptResponse> secondReceipt = receive(
                secondOrder.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(secondOrder.items().get(0).id()), "quantityReceived", 50, "unitPrice", 16)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        assertThat(secondReceipt.getBody().inventoryUpdates().get(0).quantityOnHand()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(secondReceipt.getBody().inventoryUpdates().get(0).averageUnitCost()).isEqualByComparingTo(new BigDecimal("12"));
    }

    // ---- Doble recepción / reintento (idempotencia) ----

    @Test
    void retryingTheSameReceiptWithSameIdempotencyKeyDoesNotDuplicateEffect() {
        String productId = createProduct("SKU-IDEMP-001");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 10, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();
        String idempotencyKey = UUID.randomUUID().toString();
        List<Map<String, Object>> items = List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 6, "unitPrice", 15));

        ResponseEntity<PurchaseReceiptResponse> first = receive(order.id(), items, idempotencyKey, operatorAToken, PurchaseReceiptResponse.class);
        ResponseEntity<PurchaseReceiptResponse> retry = receive(order.id(), items, idempotencyKey, operatorAToken, PurchaseReceiptResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().items().get(0).quantityReceived()).isEqualByComparingTo(new BigDecimal("6"));
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("6"));
    }

    @Test
    void retryingSameKeyAfterOrderBecameFullyReceivedStillReplaysTheOriginalResult() {
        // Regresión: la comprobación de idempotencia debe evaluarse ANTES que la de
        // "orden ya recibida" — un reintento legítimo de la recepción que completó la
        // orden no debe chocar con el propio estado que él mismo produjo.
        String productId = createProduct("SKU-IDEMP-003");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 50, 16, null, operatorAToken);
        String itemId = order.items().get(0).id();
        String idempotencyKey = UUID.randomUUID().toString();
        List<Map<String, Object>> items = List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 50, "unitPrice", 16));

        ResponseEntity<PurchaseReceiptResponse> first = receive(order.id(), items, idempotencyKey, operatorAToken, PurchaseReceiptResponse.class);
        assertThat(first.getBody().status()).isEqualTo(PurchaseOrderStatus.RECEIVED);

        ResponseEntity<PurchaseReceiptResponse> retry = receive(order.id(), items, idempotencyKey, operatorAToken, PurchaseReceiptResponse.class);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().status()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("50"));
    }

    @Test
    void differentIdempotencyKeyAppliesASecondLegitimateReceipt() {
        String productId = createProduct("SKU-IDEMP-002");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 10, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        receive(order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 4, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);
        receive(order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 6, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(BigDecimal.TEN);
    }

    // ---- Datos inválidos ----

    @Test
    void receivingMoreThanPendingIsRejected() {
        String productId = createProduct("SKU-INV-001");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 10, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<String> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 11, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_RECEPCION_EXCEDE_ORDENADO\"");
    }

    @Test
    void receivingNonPositiveQuantityIsRejected() {
        String productId = createProduct("SKU-INV-002");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 10, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<String> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 0, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_INVALIDA\"");
    }

    @Test
    void receivingWithAnAbsurdUnitPriceIsRejected() {
        // Auditoría de seguridad: un costo de recepción muy alejado del pactado
        // (aquí, 15 pactado vs. 999999 recibido) corrompía permanentemente el
        // costo promedio ponderado sin ninguna validación — reproducido en vivo
        // contra el stack real antes de este fix.
        String productId = createProduct("SKU-INV-PRICE-001");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 10, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<String> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 1, "unitPrice", 999999)),
                UUID.randomUUID().toString(), operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"PRECIO_RECEPCION_FUERA_DE_RANGO\"");
    }

    @Test
    void receivingWithAPriceWithinAWideButReasonableRangeIsAccepted() {
        // Una fluctuación real de precio (aquí, casi el doble del pactado) no
        // debe bloquearse — el límite existe para errores absurdos, no para
        // variaciones legítimas de mercado.
        String productId = createProduct("SKU-INV-PRICE-002");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 10, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        PurchaseReceiptResponse response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 10, "unitPrice", 28)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class).getBody();

        assertThat(response.status()).isEqualTo(PurchaseOrderStatus.RECEIVED);
    }

    @Test
    void receivingAnAlreadyReceivedOrderIsRejected() {
        String productId = createProduct("SKU-INV-003");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 5, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();
        receive(order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 5, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, PurchaseReceiptResponse.class);

        ResponseEntity<String> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 1, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"ORDEN_YA_RECIBIDA\"");
    }

    @Test
    void receivingNonexistentItemReturns404() {
        String productId = createProduct("SKU-INV-004");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 5, 15, null, operatorAToken);

        ResponseEntity<String> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", 999_999, "quantityReceived", 1, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"LINEA_ORDEN_NO_ENCONTRADA\"");
    }

    @Test
    void missingIdempotencyKeyOnReceiptIsRejected() {
        String productId = createProduct("SKU-INV-005");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 5, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/purchase-orders/" + order.id() + "/receipts", HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("items", List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 1, "unitPrice", 15))),
                        authHeaders(operatorAToken)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"IDEMPOTENCY_KEY_REQUERIDO\"");
    }

    // ---- Permisos y sucursal ----

    @Test
    void managerCanReceivePurchases() {
        // Ampliación de permisos: MANAGER queda habilitado igual que OPERATOR/ADMIN.
        String productId = createProduct("SKU-PERM-001");
        PurchaseOrderResponse order = createOrder(branchA.getId(), productId, 5, 15, null, operatorAToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<PurchaseReceiptResponse> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 1, "unitPrice", 15)),
                UUID.randomUUID().toString(), managerAToken, PurchaseReceiptResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void operatorCannotReceiveForAnotherBranch() {
        String productId = createProduct("SKU-PERM-002");
        PurchaseOrderResponse order = createOrder(branchB.getId(), productId, 5, 15, null, adminToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<String> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 1, "unitPrice", 15)),
                UUID.randomUUID().toString(), operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");
    }

    @Test
    void adminCanReceiveForAnyBranch() {
        String productId = createProduct("SKU-PERM-003");
        PurchaseOrderResponse order = createOrder(branchB.getId(), productId, 5, 15, null, adminToken);
        String itemId = order.items().get(0).id();

        ResponseEntity<PurchaseReceiptResponse> response = receive(
                order.id(), List.of(Map.of("purchaseOrderItemId", Long.valueOf(itemId), "quantityReceived", 5, "unitPrice", 15)),
                UUID.randomUUID().toString(), adminToken, PurchaseReceiptResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- helpers ----

    private String createProduct(String sku) {
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/v1/products", HttpMethod.POST,
                new HttpEntity<>(Map.of("sku", sku, "name", "Producto " + sku, "baseUnitOfMeasureId", unUnit.getId(), "minimumStock", 0, "unitPrice", 10), authHeaders(operatorAToken)),
                ProductResponse.class);
        return response.getBody().id();
    }

    private PurchaseOrderResponse createOrder(Long branchId, String productId, int quantity, int unitPrice, Integer discount, String token) {
        Map<String, Object> item = discount == null
                ? Map.of("productId", Long.valueOf(productId), "quantityOrdered", quantity, "unitPrice", unitPrice)
                : Map.of("productId", Long.valueOf(productId), "quantityOrdered", quantity, "unitPrice", unitPrice, "discountPercentage", discount);
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<PurchaseOrderResponse> response = restTemplate.exchange(
                "/api/v1/purchase-orders", HttpMethod.POST,
                new HttpEntity<>(Map.of("supplierId", supplier.getId(), "branchId", branchId, "items", List.of(item)), headers),
                PurchaseOrderResponse.class);
        return response.getBody();
    }

    private <T> ResponseEntity<T> receive(String orderId, List<Map<String, Object>> items, String idempotencyKey, String token, Class<T> responseType) {
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.exchange(
                "/api/v1/purchase-orders/" + orderId + "/receipts", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", items), headers), responseType);
    }

    private BigDecimal stockOf(String productId, Long branchId) {
        return inventoryRepository.findByProductIdAndBranchId(Long.valueOf(productId), branchId)
                .orElseThrow(() -> new AssertionError("No existe inventario para producto=" + productId + " sucursal=" + branchId))
                .getQuantityOnHand();
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
