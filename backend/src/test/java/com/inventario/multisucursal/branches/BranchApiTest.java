package com.inventario.multisucursal.branches;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventario.multisucursal.users.DeactivateUserRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC-15 (docs/USE_CASES.md): CRUD/activación de sucursales. Cubre
 * exactamente lo pedido: creación válida, duplicados, permisos, acceso a
 * sucursal ajena (aquí: escritura es ADMIN-only sin importar de qué sucursal
 * se trate, ni siquiera la propia del Gerente), operaciones sobre sucursal
 * inexistente, y la restricción de desactivar con historial inconsistente
 * (usuarios activos todavía asignados).
 *
 * <p>{@code patch(...)} usa {@link HttpClient} (JDK) en vez de
 * {@code TestRestTemplate}: este último se apoya en {@code HttpURLConnection},
 * que no soporta el verbo PATCH en absoluto ({@code ProtocolException: Invalid
 * HTTP method}) — límite conocido del JDK, no de este código (el mismo motivo
 * por el que {@code AuthenticationFlowTest} evita ese cliente para 401).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class BranchApiTest {

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
    private PasswordEncoder passwordEncoder;

    private Branch branchA;
    private Branch branchB;
    private String adminToken;
    private String managerToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branchA = branchRepository.save(new Branch("SUC-A", "Sucursal A", "Calle 1"));
        branchB = branchRepository.save(new Branch("SUC-B", "Sucursal B", "Calle 2"));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Gerente A", "manager@test.local", hash, RoleCode.MANAGER, branchA.getId()));
        userRepository.save(new User("Operador A", "operator@test.local", hash, RoleCode.OPERATOR, branchA.getId()));

        adminToken = login("admin@test.local");
        managerToken = login("manager@test.local");
        operatorToken = login("operator@test.local");
    }

    // ---- Creación válida y duplicados ----

    @Test
    void adminCanCreateBranch() {
        ResponseEntity<BranchResponse> response = post(
                "/api/v1/branches", new CreateBranchRequest("SUC-C", "Sucursal C", "Calle 3"), adminToken, BranchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().code()).isEqualTo("SUC-C");
        assertThat(response.getBody().active()).isTrue();
    }

    @Test
    void creatingBranchWithDuplicateCodeReturns409() {
        post("/api/v1/branches", new CreateBranchRequest("SUC-DUP", "Primera", null), adminToken, BranchResponse.class);

        ResponseEntity<String> response = post(
                "/api/v1/branches", new CreateBranchRequest("SUC-DUP", "Segunda", null), adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"CODIGO_YA_EXISTE\"");
    }

    // ---- Permisos / acceso a sucursal ajena ----

    @Test
    void anyAuthenticatedRoleCanReadBranches() {
        ResponseEntity<String> list = getWithToken("/api/v1/branches", operatorToken, String.class);
        ResponseEntity<BranchResponse> detail = getWithToken("/api/v1/branches/" + branchB.getId(), operatorToken, BranchResponse.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().id()).isEqualTo(String.valueOf(branchB.getId()));
    }

    @Test
    void operatorCannotCreateBranch() {
        ResponseEntity<String> response = post(
                "/api/v1/branches", new CreateBranchRequest("SUC-X", "X", null), operatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"ROL_NO_AUTORIZADO\"");
    }

    @Test
    void managerCannotUpdateEvenTheirOwnBranch() {
        // "Acceso a sucursal ajena": la escritura de sucursales es exclusiva
        // de ADMIN sin importar la sucursal - ni siquiera la propia del
        // Gerente le da permiso de administrarla (docs/API_DESIGN.md,
        // sección 6: branches.Escritura = ADMIN, no "ADMIN o dueño").
        ResponseEntity<String> response = patch(
                "/api/v1/branches/" + branchA.getId(),
                new UpdateBranchRequest("Nuevo nombre", "Nueva ubicación"),
                managerToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"ROL_NO_AUTORIZADO\"");
    }

    @Test
    void managerCannotDeactivateAnotherBranch() {
        ResponseEntity<String> response = postAction("/api/v1/branches/" + branchB.getId() + "/deactivate", managerToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- Actualización válida (ADMIN) ----

    @Test
    void adminCanUpdateBranchDetails() {
        ResponseEntity<BranchResponse> response = patch(
                "/api/v1/branches/" + branchA.getId(),
                new UpdateBranchRequest("Sucursal A Renombrada", "Nueva dirección"),
                adminToken,
                BranchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Sucursal A Renombrada");
        assertThat(response.getBody().location()).isEqualTo("Nueva dirección");
        assertThat(response.getBody().code()).isEqualTo("SUC-A");
    }

    // ---- Restricción: no desactivar con historial inconsistente ----

    @Test
    void cannotDeactivateBranchWithActiveUsersAssigned() {
        ResponseEntity<String> response = postAction("/api/v1/branches/" + branchA.getId() + "/deactivate", adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_CON_USUARIOS_ACTIVOS\"");
    }

    @Test
    void canDeactivateBranchOnceItsActiveUsersAreDeactivated() {
        post("/api/v1/users/" + findUserId("manager@test.local") + "/deactivate", new DeactivateUserRequest("Prueba"), adminToken, String.class);
        post("/api/v1/users/" + findUserId("operator@test.local") + "/deactivate", new DeactivateUserRequest("Prueba"), adminToken, String.class);

        ResponseEntity<BranchResponse> response = postAction(
                "/api/v1/branches/" + branchA.getId() + "/deactivate", adminToken, BranchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().active()).isFalse();
    }

    // ---- Eliminación ----

    @Test
    void adminCanDeleteBranchWithNoAssociatedData() {
        BranchResponse created = post("/api/v1/branches", new CreateBranchRequest("SUC-VACIA", "Sin datos", null), adminToken, BranchResponse.class)
                .getBody();
        Long branchId = Long.valueOf(created.id());

        ResponseEntity<Void> response = delete("/api/v1/branches/" + branchId, adminToken, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(branchRepository.findById(branchId)).isEmpty();
    }

    @Test
    void cannotDeleteBranchWithAssociatedUsers() {
        // branchA tiene un Gerente y un Operador asignados desde setUp().
        ResponseEntity<String> response = delete("/api/v1/branches/" + branchA.getId(), adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_CON_DATOS_ASOCIADOS\"");
        assertThat(branchRepository.findById(branchA.getId())).isPresent();
    }

    @Test
    void operatorCannotDeleteBranch() {
        ResponseEntity<String> response = delete("/api/v1/branches/" + branchB.getId(), operatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- Sucursal inexistente ----

    @Test
    void operationsOnNonexistentBranchReturn404() {
        long missingId = 999_999L;

        assertThat(getWithToken("/api/v1/branches/" + missingId, adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch("/api/v1/branches/" + missingId, new UpdateBranchRequest("X", null), adminToken, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(postAction("/api/v1/branches/" + missingId + "/deactivate", adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete("/api/v1/branches/" + missingId, adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- Paginación y filtro mínimo (docs/API_DESIGN.md, sección 7.3) ----

    @Test
    void listSupportsActiveFilterAndReturnsPageEnvelope() {
        postAction("/api/v1/branches/" + branchB.getId() + "/deactivate", adminToken, String.class);

        ResponseEntity<String> onlyActive = getWithToken("/api/v1/branches?active=true", adminToken, String.class);

        assertThat(onlyActive.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(onlyActive.getBody()).contains("\"totalElements\"", "\"page\"", "\"size\"");
        assertThat(onlyActive.getBody()).doesNotContain("\"code\":\"SUC-B\"");
    }

    // ---- helpers ----

    private Long findUserId(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId();
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

    private <T> ResponseEntity<T> postAction(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(null, authHeaders(token)), responseType);
    }

    private <T> ResponseEntity<T> delete(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.DELETE, new HttpEntity<>(authHeaders(token)), responseType);
    }

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
