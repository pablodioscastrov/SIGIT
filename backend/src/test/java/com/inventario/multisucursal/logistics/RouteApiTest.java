package com.inventario.multisucursal.logistics;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Catálogo de rutas clasificadas (RF-028). Lectura abierta; escritura MANAGER + ADMIN. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RouteApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Branch branchA;
    private Branch branchB;
    private String adminToken;
    private String managerToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        routeRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branchA = branchRepository.save(new Branch("SUC-RT-A", "Sucursal A", null));
        branchB = branchRepository.save(new Branch("SUC-RT-B", "Sucursal B", null));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Gerente", "manager@test.local", hash, RoleCode.MANAGER, branchA.getId()));
        userRepository.save(new User("Operador", "operator@test.local", hash, RoleCode.OPERATOR, branchA.getId()));

        adminToken = login("admin@test.local");
        managerToken = login("manager@test.local");
        operatorToken = login("operator@test.local");
    }

    @Test
    void managerCanClassifyARouteAndAnyRoleCanReadIt() {
        ResponseEntity<RouteResponse> created = post("/api/v1/routes",
                Map.of("originBranchId", branchA.getId(), "destinationBranchId", branchB.getId(), "classification", "PRIORITY"),
                managerToken, RouteResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().classification()).isEqualTo(RouteClassification.PRIORITY);

        ResponseEntity<String> read = getWithToken("/api/v1/routes", operatorToken, String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).contains("\"classification\":\"PRIORITY\"");
    }

    @Test
    void operatorCannotClassifyRoutes() {
        ResponseEntity<String> response = post("/api/v1/routes",
                Map.of("originBranchId", branchA.getId(), "destinationBranchId", branchB.getId(), "classification", "COST"),
                operatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"ROL_NO_AUTORIZADO\"");
    }

    @Test
    void duplicateRouteForTheSamePairIsRejected() {
        post("/api/v1/routes", Map.of("originBranchId", branchA.getId(), "destinationBranchId", branchB.getId(), "classification", "COST"),
                adminToken, RouteResponse.class);

        ResponseEntity<String> second = post("/api/v1/routes",
                Map.of("originBranchId", branchA.getId(), "destinationBranchId", branchB.getId(), "classification", "TIME"),
                adminToken, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("\"code\":\"RUTA_YA_EXISTE\"");
    }

    @Test
    void routeWithSameOriginAndDestinationIsRejected() {
        ResponseEntity<String> response = post("/api/v1/routes",
                Map.of("originBranchId", branchA.getId(), "destinationBranchId", branchA.getId(), "classification", "TIME"),
                adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"ORIGEN_IGUAL_DESTINO\"");
    }

    @Test
    void reclassifyingChangesOnlyTheClassification() {
        String id = post("/api/v1/routes",
                Map.of("originBranchId", branchA.getId(), "destinationBranchId", branchB.getId(), "classification", "COST"),
                adminToken, RouteResponse.class).getBody().id();

        ResponseEntity<RouteResponse> updated = patch("/api/v1/routes/" + id, Map.of("classification", "TIME"), managerToken, RouteResponse.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().classification()).isEqualTo(RouteClassification.TIME);
        assertThat(updated.getBody().originBranchId()).isEqualTo(String.valueOf(branchA.getId()));
        assertThat(updated.getBody().destinationBranchId()).isEqualTo(String.valueOf(branchB.getId()));
    }

    @Test
    void routesCanBeFilteredByBranchAndClassification() {
        Branch branchC = branchRepository.save(new Branch("SUC-RT-C", "Sucursal C", null));
        post("/api/v1/routes", Map.of("originBranchId", branchA.getId(), "destinationBranchId", branchB.getId(), "classification", "PRIORITY"),
                adminToken, RouteResponse.class);
        post("/api/v1/routes", Map.of("originBranchId", branchB.getId(), "destinationBranchId", branchC.getId(), "classification", "COST"),
                adminToken, RouteResponse.class);

        assertThat(getWithToken("/api/v1/routes?branchId=" + branchC.getId(), adminToken, String.class).getBody())
                .contains("\"totalElements\":1").contains("\"classification\":\"COST\"");
        assertThat(getWithToken("/api/v1/routes?classification=PRIORITY", adminToken, String.class).getBody())
                .contains("\"totalElements\":1").contains("\"classification\":\"PRIORITY\"");
    }

    @Test
    void nonexistentRouteReturns404() {
        assertThat(getWithToken("/api/v1/routes/999999", adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private String login(String email) {
        var body = new com.inventario.multisucursal.auth.LoginRequest(email, SEED_PASSWORD);
        return restTemplate.postForEntity("/api/v1/auth/login", body, com.inventario.multisucursal.auth.LoginResponse.class)
                .getBody().accessToken();
    }

    private <T> ResponseEntity<T> post(String path, Object body, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), responseType);
    }

    /** PATCH vía {@link HttpClient}: {@code HttpURLConnection} no soporta ese verbo (ver BranchApiTest). */
    private <T> ResponseEntity<T> patch(String path, Object requestBody, String token, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(restTemplate.getRootUri() + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            T body = responseType.equals(String.class)
                    ? responseType.cast(response.body())
                    : objectMapper.readValue(response.body(), responseType);
            return ResponseEntity.status(response.statusCode()).body(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
