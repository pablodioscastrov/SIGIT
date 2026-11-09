package com.inventario.multisucursal.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.products.PriceListResponse;
import com.inventario.multisucursal.products.PriceResponse;
import com.inventario.multisucursal.products.ProductResponse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flujo A (docs/CRITICAL_FLOWS.md): venta válida (uno y varios productos),
 * producto inexistente/inactivo, cantidad ≤0, stock insuficiente, descuento
 * inválido, sucursal inválida, permisos, idempotencia. La concurrencia y el
 * rollback se cubren en {@link SaleConcurrencyTest}/{@link SaleRollbackTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SaleApiTest {

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
    private com.inventario.multisucursal.products.PriceListRepository priceListRepository;

    @Autowired
    private com.inventario.multisucursal.products.PriceRepository priceRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Branch branchA;
    private Branch branchB;
    private UnitOfMeasure unUnit;
    private String priceListId;
    private String adminToken;
    private String managerAToken;
    private String operatorAToken;
    private String operatorBToken;

    @BeforeEach
    void setUp() {
        priceRepository.deleteAll();
        priceListRepository.deleteAll();
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

        HttpHeaders priceListHeaders = authHeaders(adminToken);
        ResponseEntity<PriceListResponse> priceList = restTemplate.exchange(
                "/api/v1/price-lists", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Lista Global"), priceListHeaders), PriceListResponse.class);
        priceListId = priceList.getBody().id();
    }

    // ---- Venta válida ----

    @Test
    void validSaleOfSingleProduct() {
        String productId = createProduct("SKU-SALE-001");
        setPrice(productId, "20.00");
        stockUp(productId, branchA.getId(), 10);

        ResponseEntity<SaleResponse> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 3))),
                operatorAToken, SaleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo(SaleStatus.CONFIRMED);
        assertThat(response.getBody().items()).hasSize(1);
        assertThat(response.getBody().items().get(0).lineTotal()).isEqualByComparingTo(new BigDecimal("60.0000"));
        assertThat(response.getBody().total()).isEqualByComparingTo(new BigDecimal("60.0000"));
        assertThat(response.getBody().saleNumber()).startsWith("V-");
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("7"));
    }

    @Test
    void saleInAlternateUnitScalesPriceByConversionFactor() {
        // BR-019: el precio de lista está fijado en la unidad base del producto;
        // vender en una unidad alternativa (1 caja = 12 unidades) debe escalar el
        // precio por el mismo factor que ya se aplica a la cantidad, igual que
        // MovementsPage (frontend) ya hace para mostrar la equivalencia de cantidad.
        String productId = createProduct("SKU-SALE-UNIT-001");
        setPrice(productId, "10.00");
        UnitOfMeasure cajaUnit = unitOfMeasureRepository.save(new UnitOfMeasure("CJ", "Caja"));
        addProductUnit(productId, cajaUnit.getId(), "12.000000");
        stockUp(productId, branchA.getId(), 120);

        ResponseEntity<SaleResponse> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of(
                                "productId", Long.valueOf(productId), "unitOfMeasureId", cajaUnit.getId(), "quantity", 2))),
                operatorAToken, SaleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // 10.00 por unidad base * 12 (factor de la caja) = 120.00 por caja; 2 cajas -> 240.00.
        assertThat(response.getBody().items().get(0).unitPrice()).isEqualByComparingTo(new BigDecimal("120.0000"));
        assertThat(response.getBody().items().get(0).lineTotal()).isEqualByComparingTo(new BigDecimal("240.0000"));
        assertThat(response.getBody().total()).isEqualByComparingTo(new BigDecimal("240.0000"));
        // 2 cajas * 12 = 24 unidades base descontadas del stock.
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("96"));
    }

    @Test
    void validSaleOfMultipleProducts() {
        String productA = createProduct("SKU-SALE-002A");
        String productB = createProduct("SKU-SALE-002B");
        setPrice(productA, "10.00");
        setPrice(productB, "40.00");
        stockUp(productA, branchA.getId(), 20);
        stockUp(productB, branchA.getId(), 5);

        ResponseEntity<SaleResponse> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(
                                Map.of("productId", Long.valueOf(productA), "quantity", 5, "discountPercentage", 0),
                                Map.of("productId", Long.valueOf(productB), "quantity", 2, "discountPercentage", 10))),
                operatorAToken, SaleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().items()).hasSize(2);
        // 5*10=50 + (2*40*0.9)=72 -> subtotal=130, descuento=8, total=122
        assertThat(response.getBody().subtotal()).isEqualByComparingTo(new BigDecimal("130.0000"));
        assertThat(response.getBody().discountTotal()).isEqualByComparingTo(new BigDecimal("8.0000"));
        assertThat(response.getBody().total()).isEqualByComparingTo(new BigDecimal("122.0000"));
        assertThat(stockOf(productA, branchA.getId())).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(stockOf(productB, branchA.getId())).isEqualByComparingTo(new BigDecimal("3"));
    }

    // ---- Producto inexistente/inactivo ----

    @Test
    void nonexistentProductReturns404() {
        ResponseEntity<String> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", 999_999, "quantity", 1))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"PRODUCTO_NO_ENCONTRADO\"");
    }

    @Test
    void inactiveProductReturns409() {
        String productId = createProduct("SKU-SALE-003");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        restTemplate.exchange("/api/v1/products/" + productId + "/deactivate", HttpMethod.POST,
                new HttpEntity<>(null, authHeaders(operatorAToken)), ProductResponse.class);

        ResponseEntity<String> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 1))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"PRODUCTO_INACTIVO\"");
    }

    // ---- Cantidad ≤0 ----

    @Test
    void nonPositiveQuantityIsRejected() {
        String productId = createProduct("SKU-SALE-004");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);

        ResponseEntity<String> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 0))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_INVALIDA\"");
    }

    // ---- Stock insuficiente ----

    @Test
    void insufficientStockIsRejected() {
        String productId = createProduct("SKU-SALE-005");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 3);

        ResponseEntity<String> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 5))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"STOCK_INSUFICIENTE\"");
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    void sellingWithNoStockAtAllIsRejected() {
        String productId = createProduct("SKU-SALE-006");
        setPrice(productId, "15.00");

        ResponseEntity<String> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 1))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"STOCK_INSUFICIENTE\"");
    }

    // ---- Descuento inválido ----

    @Test
    void discountAbove100IsRejected() {
        String productId = createProduct("SKU-SALE-007");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);

        ResponseEntity<String> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 1, "discountPercentage", 150))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fullDiscountLeavesLineTotalAtZero() {
        String productId = createProduct("SKU-SALE-008");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);

        ResponseEntity<SaleResponse> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 2, "discountPercentage", 100))),
                operatorAToken, SaleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().items().get(0).lineTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- Sucursal / usuario inválidos ----

    @Test
    void nonexistentBranchReturns404() {
        String productId = createProduct("SKU-SALE-009");
        setPrice(productId, "15.00");

        ResponseEntity<String> response = sell(
                Map.of("branchId", 999_999, "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 1))),
                adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_ENCONTRADA\"");
    }

    @Test
    void managerCanSell() {
        // BR-053: ampliación explícita — MANAGER puede generar y gestionar ventas, igual que OPERATOR/ADMIN.
        String productId = createProduct("SKU-SALE-010");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);

        ResponseEntity<SaleResponse> response = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 1))),
                managerAToken, SaleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void operatorCannotSellForAnotherBranch() {
        String productId = createProduct("SKU-SALE-011");
        setPrice(productId, "15.00");
        stockUp(productId, branchB.getId(), 10);

        ResponseEntity<String> response = sell(
                Map.of("branchId", branchB.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 1))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");
    }

    @Test
    void adminCanSellForAnyBranch() {
        String productId = createProduct("SKU-SALE-012");
        setPrice(productId, "15.00");
        stockUp(productId, branchB.getId(), 10);

        ResponseEntity<SaleResponse> response = sell(
                Map.of("branchId", branchB.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 1))),
                adminToken, SaleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- Idempotencia ----

    @Test
    void missingIdempotencyKeyIsRejected() {
        String productId = createProduct("SKU-SALE-013");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sales", HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                                "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 1))),
                        authHeaders(operatorAToken)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"IDEMPOTENCY_KEY_REQUERIDO\"");
    }

    @Test
    void retryingSameIdempotencyKeyDoesNotCreateASecondSale() {
        String productId = createProduct("SKU-SALE-014");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 4)));

        ResponseEntity<SaleResponse> first = sellWithKey(body, idempotencyKey, operatorAToken);
        ResponseEntity<SaleResponse> retry = sellWithKey(body, idempotencyKey, operatorAToken);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("6"));
    }

    // ---- Movimiento auditable / consulta ----

    @Test
    void movementIsLinkedToSaleItem() {
        String productId = createProduct("SKU-SALE-015");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);

        sell(Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 2))), operatorAToken, SaleResponse.class);

        ResponseEntity<String> movements = getWithToken(
                "/api/v1/inventory-movements?branchId=" + branchA.getId() + "&productId=" + productId + "&reason=VENTA", adminToken, String.class);

        assertThat(movements.getBody())
                .contains("\"reason\":\"VENTA\"")
                .contains("\"direction\":\"RETIRO\"")
                .contains("\"type\":\"SALE\"");
    }

    @Test
    void listOnlyReturnsOwnBranchSalesForNonAdmin() {
        String productA = createProduct("SKU-SALE-016A");
        String productB = createProduct("SKU-SALE-016B");
        setPrice(productA, "10.00");
        setPrice(productB, "10.00");
        stockUp(productA, branchA.getId(), 5);
        stockUp(productB, branchB.getId(), 5);

        sell(Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                "items", List.of(Map.of("productId", Long.valueOf(productA), "quantity", 1))), operatorAToken, SaleResponse.class);
        sell(Map.of("branchId", branchB.getId(), "priceListId", Long.valueOf(priceListId),
                "items", List.of(Map.of("productId", Long.valueOf(productB), "quantity", 1))), operatorBToken, SaleResponse.class);

        ResponseEntity<String> response = getWithToken("/api/v1/sales?branchId=" + branchB.getId(), operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"branchId\":\"" + branchA.getId() + "\"");
        assertThat(response.getBody()).doesNotContain("\"branchId\":\"" + branchB.getId() + "\"");
    }

    @Test
    void operationsOnNonexistentSaleReturn404() {
        assertThat(getWithToken("/api/v1/sales/999999", adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- Responsable de la venta (BR-054) ----

    @Test
    void saleExposesTheNameOfWhoGeneratedIt() {
        String productId = createProduct("SKU-SALE-017");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);

        ResponseEntity<SaleResponse> created = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 1))),
                operatorAToken, SaleResponse.class);
        assertThat(created.getBody().soldByUserName()).isEqualTo("Operador A");

        ResponseEntity<SaleResponse> fetched = getWithToken("/api/v1/sales/" + created.getBody().id(), adminToken, SaleResponse.class);
        assertThat(fetched.getBody().soldByUserName()).isEqualTo("Operador A");

        ResponseEntity<String> list = getWithToken("/api/v1/sales?branchId=" + branchA.getId(), adminToken, String.class);
        assertThat(list.getBody()).contains("\"soldByUserName\":\"Operador A\"");
    }

    // ---- Devolución de venta (BR-052) ----

    @Test
    void operatorCanReturnPartOfASoldItemAndStockIncreasesBack() {
        String productId = createProduct("SKU-RET-001");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        ResponseEntity<SaleResponse> sale = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 5))),
                operatorAToken, SaleResponse.class);
        String saleItemId = sale.getBody().items().get(0).id();
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("5"));

        ResponseEntity<SaleReturnResponse> response = returnSale(
                sale.getBody().id(),
                Map.of("items", List.of(Map.of("saleItemId", Long.valueOf(saleItemId), "quantity", 2))),
                operatorAToken, SaleReturnResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().items()).hasSize(1);
        assertThat(response.getBody().items().get(0).quantityReturned()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(response.getBody().items().get(0).pending()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(response.getBody().inventoryUpdates().get(0).quantityOnHand()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("7"));

        ResponseEntity<SaleResponse> reread = getWithToken("/api/v1/sales/" + sale.getBody().id(), adminToken, SaleResponse.class);
        assertThat(reread.getBody().items().get(0).quantityReturned()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(reread.getBody().items().get(0).pending()).isEqualByComparingTo(new BigDecimal("3"));
        // El comprobante original no se toca (BR-021): la devolución no recalcula subtotal/total ni el estado.
        assertThat(reread.getBody().total()).isEqualByComparingTo(new BigDecimal("75.0000"));
        assertThat(reread.getBody().status()).isEqualTo(SaleStatus.CONFIRMED);
    }

    @Test
    void returningMoreThanSoldIsRejected() {
        String productId = createProduct("SKU-RET-002");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        ResponseEntity<SaleResponse> sale = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 3))),
                operatorAToken, SaleResponse.class);
        String saleItemId = sale.getBody().items().get(0).id();

        ResponseEntity<String> response = returnSale(
                sale.getBody().id(),
                Map.of("items", List.of(Map.of("saleItemId", Long.valueOf(saleItemId), "quantity", 99))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_DEVOLUCION_EXCEDE_VENDIDO\"");
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("7"));
    }

    @Test
    void returningNonPositiveQuantityIsRejected() {
        String productId = createProduct("SKU-RET-003");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        ResponseEntity<SaleResponse> sale = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 3))),
                operatorAToken, SaleResponse.class);
        String saleItemId = sale.getBody().items().get(0).id();

        ResponseEntity<String> response = returnSale(
                sale.getBody().id(),
                Map.of("items", List.of(Map.of("saleItemId", Long.valueOf(saleItemId), "quantity", 0))),
                operatorAToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_INVALIDA\"");
    }

    @Test
    void missingIdempotencyKeyOnReturnIsRejected() {
        String productId = createProduct("SKU-RET-004");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        ResponseEntity<SaleResponse> sale = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 3))),
                operatorAToken, SaleResponse.class);
        String saleItemId = sale.getBody().items().get(0).id();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sales/" + sale.getBody().id() + "/returns", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", List.of(Map.of("saleItemId", Long.valueOf(saleItemId), "quantity", 1))), authHeaders(operatorAToken)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"IDEMPOTENCY_KEY_REQUERIDO\"");
    }

    @Test
    void retryingSameReturnIdempotencyKeyDoesNotApplyItTwice() {
        String productId = createProduct("SKU-RET-005");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        ResponseEntity<SaleResponse> sale = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 5))),
                operatorAToken, SaleResponse.class);
        String saleItemId = sale.getBody().items().get(0).id();
        Map<String, Object> body = Map.of("items", List.of(Map.of("saleItemId", Long.valueOf(saleItemId), "quantity", 2)));
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<SaleReturnResponse> first = returnSaleWithKey(sale.getBody().id(), body, idempotencyKey, operatorAToken);
        ResponseEntity<SaleReturnResponse> retry = returnSaleWithKey(sale.getBody().id(), body, idempotencyKey, operatorAToken);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().items().get(0).quantityReturned()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(stockOf(productId, branchA.getId())).isEqualByComparingTo(new BigDecimal("7"));
    }

    @Test
    void returnMovementIsLinkedToTheSaleItemWithReasonDevolucion() {
        String productId = createProduct("SKU-RET-006");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        ResponseEntity<SaleResponse> sale = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 4))),
                operatorAToken, SaleResponse.class);
        String saleItemId = sale.getBody().items().get(0).id();

        returnSale(sale.getBody().id(), Map.of("items", List.of(Map.of("saleItemId", Long.valueOf(saleItemId), "quantity", 1))), operatorAToken, SaleReturnResponse.class);

        ResponseEntity<String> movements = getWithToken(
                "/api/v1/inventory-movements?branchId=" + branchA.getId() + "&productId=" + productId + "&reason=DEVOLUCION", adminToken, String.class);
        assertThat(movements.getBody())
                .contains("\"reason\":\"DEVOLUCION\"")
                .contains("\"direction\":\"INGRESO\"")
                .contains("\"type\":\"SALE\"")
                .contains("\"id\":\"" + saleItemId + "\"");
    }

    @Test
    void managerCanReturnASale() {
        String productId = createProduct("SKU-RET-007");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        ResponseEntity<SaleResponse> sale = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 3))),
                operatorAToken, SaleResponse.class);
        String saleItemId = sale.getBody().items().get(0).id();

        ResponseEntity<SaleReturnResponse> response = returnSale(
                sale.getBody().id(),
                Map.of("items", List.of(Map.of("saleItemId", Long.valueOf(saleItemId), "quantity", 1))),
                managerAToken, SaleReturnResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void operatorCannotReturnASaleFromAnotherBranch() {
        String productId = createProduct("SKU-RET-008");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        ResponseEntity<SaleResponse> sale = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 3))),
                operatorAToken, SaleResponse.class);
        String saleItemId = sale.getBody().items().get(0).id();

        ResponseEntity<String> response = returnSale(
                sale.getBody().id(),
                Map.of("items", List.of(Map.of("saleItemId", Long.valueOf(saleItemId), "quantity", 1))),
                operatorBToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");
    }

    @Test
    void returningFromNonexistentSaleOrLineReturns404() {
        String productId = createProduct("SKU-RET-009");
        setPrice(productId, "15.00");
        stockUp(productId, branchA.getId(), 10);
        ResponseEntity<SaleResponse> sale = sell(
                Map.of("branchId", branchA.getId(), "priceListId", Long.valueOf(priceListId),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantity", 3))),
                operatorAToken, SaleResponse.class);
        String saleItemId = sale.getBody().items().get(0).id();

        assertThat(returnSale(999_999L, Map.of("items", List.of(Map.of("saleItemId", Long.valueOf(saleItemId), "quantity", 1))), operatorAToken, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(returnSale(sale.getBody().id(), Map.of("items", List.of(Map.of("saleItemId", 999_999, "quantity", 1))), operatorAToken, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private String createProduct(String sku) {
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/v1/products", HttpMethod.POST,
                new HttpEntity<>(Map.of("sku", sku, "name", "Producto " + sku, "baseUnitOfMeasureId", unUnit.getId(), "minimumStock", 0, "unitPrice", 10), authHeaders(operatorAToken)),
                ProductResponse.class);
        return response.getBody().id();
    }

    private void setPrice(String productId, String unitPrice) {
        HttpHeaders headers = authHeaders(adminToken);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        restTemplate.exchange("/api/v1/price-lists/" + priceListId + "/prices", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId", Long.valueOf(productId), "unitPrice", new BigDecimal(unitPrice)), headers),
                PriceResponse.class);
    }

    private void addProductUnit(String productId, Long unitOfMeasureId, String conversionFactor) {
        restTemplate.exchange("/api/v1/products/" + productId + "/units", HttpMethod.POST,
                new HttpEntity<>(Map.of("unitOfMeasureId", unitOfMeasureId, "conversionFactorToBase", new BigDecimal(conversionFactor)), authHeaders(operatorAToken)),
                Object.class);
    }

    private void stockUp(String productId, Long branchId, int quantity) {
        restTemplate.exchange("/api/v1/inventory/adjustments", HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("branchId", branchId, "productId", Long.valueOf(productId), "direction", "INGRESO", "quantity", quantity, "notes", "Carga inicial de prueba"),
                        authHeaders(adminToken)),
                Object.class);
    }

    private BigDecimal stockOf(String productId, Long branchId) {
        return inventoryRepository.findByProductIdAndBranchId(Long.valueOf(productId), branchId)
                .orElseThrow(() -> new AssertionError("No existe inventario para producto=" + productId + " sucursal=" + branchId))
                .getQuantityOnHand();
    }

    private <T> ResponseEntity<T> sell(Object body, String token, Class<T> responseType) {
        return sellWithKey(body, UUID.randomUUID().toString(), token, responseType);
    }

    private ResponseEntity<SaleResponse> sellWithKey(Object body, String idempotencyKey, String token) {
        return sellWithKey(body, idempotencyKey, token, SaleResponse.class);
    }

    private <T> ResponseEntity<T> sellWithKey(Object body, String idempotencyKey, String token, Class<T> responseType) {
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.exchange("/api/v1/sales", HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
    }

    private <T> ResponseEntity<T> returnSale(String saleId, Object body, String token, Class<T> responseType) {
        return returnSaleWithKey(saleId, body, UUID.randomUUID().toString(), token, responseType);
    }

    private <T> ResponseEntity<T> returnSale(Long saleId, Object body, String token, Class<T> responseType) {
        return returnSale(String.valueOf(saleId), body, token, responseType);
    }

    private ResponseEntity<SaleReturnResponse> returnSaleWithKey(String saleId, Object body, String idempotencyKey, String token) {
        return returnSaleWithKey(saleId, body, idempotencyKey, token, SaleReturnResponse.class);
    }

    private <T> ResponseEntity<T> returnSaleWithKey(String saleId, Object body, String idempotencyKey, String token, Class<T> responseType) {
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.exchange("/api/v1/sales/" + saleId + "/returns", HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
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
