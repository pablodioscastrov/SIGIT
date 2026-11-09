package com.inventario.multisucursal.products;

import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
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

/** RF-020; docs/DOMAIN_MODEL.md, secciones 2.13/2.14. Ciclo mínimo necesario para soportar `sales`. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PriceListApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String operatorToken;
    private UnitOfMeasure unUnit;

    @BeforeEach
    void setUp() {
        unitOfMeasureRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();

        Branch branch = branchRepository.save(new Branch("SUC-PL", "Sucursal Precios", null));
        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Operador", "operator@test.local", hash, RoleCode.OPERATOR, branch.getId()));

        adminToken = login("admin@test.local");
        operatorToken = login("operator@test.local");
        unUnit = unitOfMeasureRepository.save(new UnitOfMeasure("UN", "Unidad"));
    }

    @Test
    void adminCanCreatePriceListButOperatorCannot() {
        ResponseEntity<PriceListResponse> adminResponse = post("/api/v1/price-lists", Map.of("name", "Lista A"), adminToken, PriceListResponse.class);
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(adminResponse.getBody().active()).isTrue();

        ResponseEntity<String> operatorResponse = post("/api/v1/price-lists", Map.of("name", "Lista B"), operatorToken, String.class);
        assertThat(operatorResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void settingAPriceClosesThePreviousOne() {
        String productId = createProduct("SKU-PL-001");
        String priceListId = post("/api/v1/price-lists", Map.of("name", "Lista Precio"), adminToken, PriceListResponse.class).getBody().id();

        setPrice(priceListId, productId, "10.00");
        setPrice(priceListId, productId, "12.50");

        List<PriceResponse> current = getPrices(priceListId, false);
        assertThat(current).hasSize(1);
        assertThat(current.get(0).unitPrice()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(current.get(0).validTo()).isNull();

        List<PriceResponse> history = getPrices(priceListId, true);
        assertThat(history).hasSize(2);
        assertThat(history.stream().filter(p -> p.validTo() != null)).hasSize(1);
    }

    @Test
    void anyAuthenticatedRoleCanReadPriceLists() {
        post("/api/v1/price-lists", Map.of("name", "Lista Lectura"), adminToken, PriceListResponse.class);

        ResponseEntity<String> response = getWithToken("/api/v1/price-lists", operatorToken, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Lista Lectura");
    }

    @Test
    void missingIdempotencyKeyOnSetPriceIsRejected() {
        String productId = createProduct("SKU-PL-002");
        String priceListId = post("/api/v1/price-lists", Map.of("name", "Lista Sin Idemp"), adminToken, PriceListResponse.class).getBody().id();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/price-lists/" + priceListId + "/prices", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId", Long.valueOf(productId), "unitPrice", new BigDecimal("5.00")), authHeaders(adminToken)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"IDEMPOTENCY_KEY_REQUERIDO\"");
    }

    // ---- helpers ----

    private String createProduct(String sku) {
        ResponseEntity<ProductResponse> response = post(
                "/api/v1/products", Map.of("sku", sku, "name", "Producto " + sku, "baseUnitOfMeasureId", unUnit.getId(), "minimumStock", 0, "unitPrice", 10), operatorToken, ProductResponse.class);
        return response.getBody().id();
    }

    private void setPrice(String priceListId, String productId, String unitPrice) {
        HttpHeaders headers = authHeaders(adminToken);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        restTemplate.exchange("/api/v1/price-lists/" + priceListId + "/prices", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId", Long.valueOf(productId), "unitPrice", new BigDecimal(unitPrice)), headers),
                PriceResponse.class);
    }

    @SuppressWarnings("unchecked")
    private List<PriceResponse> getPrices(String priceListId, boolean includeHistory) {
        ResponseEntity<PriceResponse[]> response = restTemplate.exchange(
                "/api/v1/price-lists/" + priceListId + "/prices?includeHistory=" + includeHistory, HttpMethod.GET,
                new HttpEntity<>(authHeaders(adminToken)), PriceResponse[].class);
        return List.of(response.getBody());
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
}
