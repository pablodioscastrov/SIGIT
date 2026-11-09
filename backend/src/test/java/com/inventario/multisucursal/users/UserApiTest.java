package com.inventario.multisucursal.users;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventario.multisucursal.auth.LoginRequest;
import com.inventario.multisucursal.auth.LoginResponse;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * UC-14 (docs/USE_CASES.md): crear, consultar, editar, activar/desactivar y
 * eliminar usuarios asignando rol y sucursal. Cubre exactamente lo pedido:
 * creación válida, duplicados, permisos (ADMIN-only, ni siquiera lectura
 * para otros roles), asociación usuario-sucursal (crear y reasignar),
 * desactivación con motivo obligatorio (que se limpia al reactivar),
 * bloqueo de autogestión (un ADMIN no puede desactivarse ni eliminarse a sí
 * mismo), eliminación real solo cuando no hay historial asociado (se apoya
 * en las FK `ON DELETE RESTRICT` ya declaradas en el esquema), y operaciones
 * sobre usuario inexistente.
 *
 * <p>{@code patch(...)} usa {@link HttpClient} (JDK) en vez de
 * {@code TestRestTemplate}, que no soporta el verbo PATCH sobre
 * {@code HttpURLConnection} — ver la misma nota en {@code BranchApiTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class UserApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Branch branchA;
    private Branch branchB;
    private String adminToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branchA = branchRepository.save(new Branch("SUC-A", "Sucursal A"));
        branchB = branchRepository.save(new Branch("SUC-B", "Sucursal B"));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Operador A", "operator@test.local", hash, RoleCode.OPERATOR, branchA.getId()));

        adminToken = login("admin@test.local");
        operatorToken = login("operator@test.local");
    }

    // ---- Creación válida y asociación usuario-sucursal ----

    @Test
    void adminCanCreateUserAssociatedToBranch() {
        var request = new CreateUserRequest("Nuevo Operador", "nuevo@test.local", "ChangeMe123!", RoleCode.OPERATOR, branchA.getId());

        ResponseEntity<UserResponse> response = post("/api/v1/users", request, adminToken, UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().role()).isEqualTo("OPERATOR");
        assertThat(response.getBody().branchId()).isEqualTo(String.valueOf(branchA.getId()));
        assertThat(response.getBody().active()).isTrue();
    }

    @Test
    void adminCanCreateAdminWithoutBranch() {
        var request = new CreateUserRequest("Otro Admin", "otroadmin@test.local", "ChangeMe123!", RoleCode.ADMIN, null);

        ResponseEntity<UserResponse> response = post("/api/v1/users", request, adminToken, UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().branchId()).isNull();
    }

    @Test
    void adminCanReassignUserToAnotherBranch() {
        Long userId = userRepository.findByEmail("operator@test.local").orElseThrow().getId();

        ResponseEntity<UserResponse> response = patch(
                "/api/v1/users/" + userId,
                new UpdateUserRequest("Operador A", "operator@test.local", RoleCode.OPERATOR, branchB.getId()),
                adminToken,
                UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().branchId()).isEqualTo(String.valueOf(branchB.getId()));
    }

    // ---- Edición de correo (BR-058) ----

    @Test
    void adminCanUpdateAUsersEmail() {
        Long userId = userRepository.findByEmail("operator@test.local").orElseThrow().getId();

        ResponseEntity<UserResponse> response = patch(
                "/api/v1/users/" + userId,
                new UpdateUserRequest("Operador A", "operador.nuevo@test.local", RoleCode.OPERATOR, branchA.getId()),
                adminToken,
                UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().email()).isEqualTo("operador.nuevo@test.local");
    }

    @Test
    void updatingToAnEmailAlreadyUsedByAnotherUserReturns409() {
        Long userId = userRepository.findByEmail("operator@test.local").orElseThrow().getId();

        ResponseEntity<String> response = patch(
                "/api/v1/users/" + userId,
                new UpdateUserRequest("Operador A", "admin@test.local", RoleCode.OPERATOR, branchA.getId()),
                adminToken,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"EMAIL_YA_EXISTE\"");
    }

    @Test
    void keepingTheSameEmailOnUpdateIsNotTreatedAsADuplicate() {
        Long userId = userRepository.findByEmail("operator@test.local").orElseThrow().getId();

        ResponseEntity<UserResponse> response = patch(
                "/api/v1/users/" + userId,
                new UpdateUserRequest("Operador Renombrado", "operator@test.local", RoleCode.OPERATOR, branchA.getId()),
                adminToken,
                UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Operador Renombrado");
    }

    // ---- Duplicados ----

    @Test
    void creatingUserWithDuplicateEmailReturns409() {
        var request = new CreateUserRequest("Otro", "operator@test.local", "ChangeMe123!", RoleCode.OPERATOR, branchA.getId());

        ResponseEntity<String> response = post("/api/v1/users", request, adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"EMAIL_YA_EXISTE\"");
    }

    // ---- Consistencia rol/sucursal ----

    @Test
    void creatingAdminWithBranchIdIsRejected() {
        var request = new CreateUserRequest("Admin Malo", "adminmalo@test.local", "ChangeMe123!", RoleCode.ADMIN, branchA.getId());

        ResponseEntity<String> response = post("/api/v1/users", request, adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"ADMIN_SIN_SUCURSAL\"");
    }

    @Test
    void creatingNonAdminWithoutBranchIsRejected() {
        var request = new CreateUserRequest("Operador Malo", "opmalo@test.local", "ChangeMe123!", RoleCode.OPERATOR, null);

        ResponseEntity<String> response = post("/api/v1/users", request, adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_REQUERIDA\"");
    }

    @Test
    void creatingUserWithNonexistentBranchReturns404() {
        var request = new CreateUserRequest("X", "x@test.local", "ChangeMe123!", RoleCode.OPERATOR, 999_999L);

        ResponseEntity<String> response = post("/api/v1/users", request, adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_ENCONTRADA\"");
    }

    @Test
    void creatingUserWithInactiveBranchIsRejected() {
        postAction("/api/v1/branches/" + branchB.getId() + "/deactivate", adminToken, String.class);
        var request = new CreateUserRequest("X", "x2@test.local", "ChangeMe123!", RoleCode.OPERATOR, branchB.getId());

        ResponseEntity<String> response = post("/api/v1/users", request, adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_INACTIVA\"");
    }

    @Test
    void creatingUserWithUnknownRoleStringReturns400() {
        HttpHeaders headers = authHeaders(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String malformedBody = """
                {"name":"X","email":"x3@test.local","password":"ChangeMe123!","role":"SUPERUSER","branchId":null}""";

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST, new HttpEntity<>(malformedBody, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"VALIDATION_ERROR\"");
    }

    // ---- Permisos ----

    @Test
    void nonAdminCannotEvenReadUsers() {
        ResponseEntity<String> response = getWithToken("/api/v1/users", operatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"ROL_NO_AUTORIZADO\"");
    }

    @Test
    void nonAdminCannotCreateUsers() {
        var request = new CreateUserRequest("X", "x4@test.local", "ChangeMe123!", RoleCode.OPERATOR, branchA.getId());

        ResponseEntity<String> response = post("/api/v1/users", request, operatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- Activación / desactivación ----

    @Test
    void adminCanDeactivateAndReactivateUser() {
        Long userId = userRepository.findByEmail("operator@test.local").orElseThrow().getId();

        ResponseEntity<UserResponse> deactivated =
                post("/api/v1/users/" + userId + "/deactivate", new DeactivateUserRequest("Renuncia"), adminToken, UserResponse.class);
        assertThat(deactivated.getBody().active()).isFalse();
        assertThat(deactivated.getBody().deactivationReason()).isEqualTo("Renuncia");

        ResponseEntity<UserResponse> reactivated = postAction("/api/v1/users/" + userId + "/activate", adminToken, UserResponse.class);
        assertThat(reactivated.getBody().active()).isTrue();
        // El motivo describía la desactivación anterior; reactivar lo limpia,
        // no debe seguir mostrándose como si aplicara todavía.
        assertThat(reactivated.getBody().deactivationReason()).isNull();
    }

    @Test
    void deactivatingWithoutReasonIsRejected() {
        Long userId = userRepository.findByEmail("operator@test.local").orElseThrow().getId();

        ResponseEntity<String> response =
                post("/api/v1/users/" + userId + "/deactivate", new DeactivateUserRequest(" "), adminToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"VALIDATION_ERROR\"");
    }

    @Test
    void adminCannotDeactivateOrDeleteOwnAccount() {
        Long adminId = userRepository.findByEmail("admin@test.local").orElseThrow().getId();

        ResponseEntity<String> deactivateResponse =
                post("/api/v1/users/" + adminId + "/deactivate", new DeactivateUserRequest("Motivo"), adminToken, String.class);
        assertThat(deactivateResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(deactivateResponse.getBody()).contains("\"code\":\"NO_AUTOGESTION\"");

        ResponseEntity<String> deleteResponse = delete("/api/v1/users/" + adminId, adminToken, String.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(deleteResponse.getBody()).contains("\"code\":\"NO_AUTOGESTION\"");
    }

    // ---- Eliminación ----

    @Test
    void adminCanDeleteUserWithNoHistory() {
        Long userId = userRepository.findByEmail("operator@test.local").orElseThrow().getId();

        ResponseEntity<Void> response = delete("/api/v1/users/" + userId, adminToken, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userRepository.findById(userId)).isEmpty();
    }

    // No hay una prueba H2 equivalente a "no se puede eliminar un usuario con
    // historial asociado": esa protección depende íntegramente de las FK
    // `ON DELETE RESTRICT` que declara el esquema real de PostgreSQL, y
    // Hibernate no las genera en el `create-drop` de pruebas porque el modelo
    // no usa asociaciones JPA (ver UserService.delete). Verificada en vivo
    // contra PostgreSQL real, igual que FlywayMigrationIntegrationTest.

    // ---- Usuario inexistente ----

    @Test
    void operationsOnNonexistentUserReturn404() {
        long missingId = 999_999L;

        assertThat(getWithToken("/api/v1/users/" + missingId, adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch("/api/v1/users/" + missingId, new UpdateUserRequest("X", "x@test.local", RoleCode.OPERATOR, branchA.getId()), adminToken, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/users/" + missingId + "/deactivate", new DeactivateUserRequest("Motivo"), adminToken, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete("/api/v1/users/" + missingId, adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private String login(String email) {
        ResponseEntity<LoginResponse> response =
                restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, SEED_PASSWORD), LoginResponse.class);
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
