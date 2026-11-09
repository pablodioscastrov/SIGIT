package com.inventario.multisucursal.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Suite mínima exigida para el módulo auth: login válido/inválido, token
 * ausente/expirado/manipulado, acceso permitido/denegado por rol, y acceso
 * denegado a sucursal ajena (BR-018). Usa H2 en memoria con esquema generado
 * por Hibernate (no Flyway/PostgreSQL) — no hay ninguna regla específica de
 * PostgreSQL en juego aquí (eso ya lo cubre FlywayMigrationIntegrationTest
 * para las migraciones V1-V4), solo el mecanismo de Spring Security + JWT.
 *
 * <p>Las llamadas a {@code POST /auth/login} usan {@link HttpClient} (JDK)
 * en vez de {@code TestRestTemplate} porque este último se apoya en
 * {@code HttpURLConnection}, que tiene un problema conocido del JDK:
 * "cannot retry due to server authentication, in streaming mode" al recibir
 * un 401 en respuesta a un POST con body — nada específico de este proyecto,
 * solo un límite de esa implementación. Las peticiones GET (sin body) sí
 * usan {@code TestRestTemplate} normalmente, porque ese caso no lo afecta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AuthenticationFlowTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Branch branchA;
    private Branch branchB;

    @BeforeEach
    void seedFixtures() {
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branchA = branchRepository.save(new Branch("SUC-A", "Sucursal A"));
        branchB = branchRepository.save(new Branch("SUC-B", "Sucursal B"));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Gerente A", "manager@test.local", hash, RoleCode.MANAGER, branchA.getId()));
        userRepository.save(new User("Operador A", "operator@test.local", hash, RoleCode.OPERATOR, branchA.getId()));
        User inactive = new User("Inactivo", "inactive@test.local", hash, RoleCode.OPERATOR, branchA.getId());
        userRepository.save(inactive);
        deactivate(inactive);
    }

    private void deactivate(User user) {
        // No hay setter público de `active` (por diseño, ver User: se
        // gestionaría vía una acción explícita del futuro módulo `users`,
        // no editando el campo directamente) - se simula una cuenta ya
        // desactivada con reflexión, solo para esta prueba.
        org.springframework.test.util.ReflectionTestUtils.setField(user, "active", false);
        userRepository.save(user);
    }

    // ---- Login válido / inválido ----

    @Test
    void loginWithValidCredentialsReturnsTokenAndUserSummary() throws Exception {
        LoginResult result = login("operator@test.local", SEED_PASSWORD);

        assertThat(result.status()).isEqualTo(200);
        LoginResponse body = result.as(LoginResponse.class);
        assertThat(body.accessToken()).isNotBlank();
        assertThat(body.expiresIn()).isEqualTo(jwtProperties.expirationMs() / 1000);
        assertThat(body.user().role()).isEqualTo("OPERATOR");
        assertThat(body.user().branchId()).isEqualTo(String.valueOf(branchA.getId()));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        LoginResult result = login("operator@test.local", "wrong-password");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.rawBody()).contains("\"code\":\"CREDENCIALES_INVALIDAS\"");
    }

    @Test
    void loginWithNonexistentEmailReturns401() throws Exception {
        // Mismo codigo/mensaje que una contraseña incorrecta - no se revela
        // si el correo existe (evita enumeración de usuarios).
        LoginResult result = login("no-existe@test.local", SEED_PASSWORD);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.rawBody()).contains("\"code\":\"CREDENCIALES_INVALIDAS\"");
    }

    @Test
    void loginWithInactiveUserReturnsDisabledAccountError() throws Exception {
        // BR-055: distinto por instrucción explícita del resto de fallos de login.
        LoginResult result = login("inactive@test.local", SEED_PASSWORD);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.rawBody()).contains("\"code\":\"CUENTA_DESACTIVADA\"");
    }

    @Test
    void loginWithInactiveUserAndWrongPasswordStillReturnsDisabledAccountError() throws Exception {
        // DaoAuthenticationProvider comprueba isEnabled() antes que la contraseña.
        LoginResult result = login("inactive@test.local", "wrong-password");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.rawBody()).contains("\"code\":\"CUENTA_DESACTIVADA\"");
    }

    @Test
    void repeatedFailedLoginsAgainstTheSameEmailAreEventuallyRateLimited() throws Exception {
        // Auditoría de seguridad: /auth/login no tenía ningún límite de
        // intentos — un correo propio de esta prueba (no compartido con otras)
        // para no dejar el bloqueo activo sobre "operator@test.local" y afectar
        // otras pruebas de esta misma clase, ya que LoginRateLimiter vive en un
        // bean singleton reutilizado entre métodos de prueba.
        String targetEmail = "rate-limit-target@test.local";
        for (int attempt = 1; attempt <= 5; attempt++) {
            LoginResult result = login(targetEmail, "wrong-password");
            assertThat(result.status()).as("intento %d debe rechazarse por credenciales, no por límite todavía", attempt).isEqualTo(401);
            assertThat(result.rawBody()).contains("\"code\":\"CREDENCIALES_INVALIDAS\"");
        }

        LoginResult blocked = login(targetEmail, "wrong-password");
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.rawBody()).contains("\"code\":\"DEMASIADOS_INTENTOS\"");

        // El bloqueo es por correo: otra cuenta sigue pudiendo iniciar sesión con normalidad.
        LoginResult unaffected = login("operator@test.local", SEED_PASSWORD);
        assertThat(unaffected.status()).isEqualTo(200);
    }

    // ---- Token ausente / expirado / manipulado ----

    @Test
    void protectedEndpointWithoutTokenReturns401() {
        ResponseEntity<String> response = restTemplate.getForEntity("/test/authz/admin-only", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"NO_AUTENTICADO\"");
        // Este camino (sin token en absoluto) pasa por JsonAuthenticationEntryPoint,
        // que escribe la respuesta directamente sobre HttpServletResponse en vez de
        // por el HttpMessageConverter de Spring MVC - verificar el acento detecta
        // una regresión real ya encontrada (charset por defecto ISO-8859-1 del
        // servlet, ver ApiErrorSupport).
        assertThat(response.getBody()).contains("inválido");
    }

    @Test
    void protectedEndpointWithExpiredTokenReturns401() {
        String expiredToken = buildRawToken("1", "ADMIN", null, Instant.now().minusSeconds(3600));

        ResponseEntity<String> response = getWithToken("/test/authz/admin-only", expiredToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"NO_AUTENTICADO\"");
    }

    @Test
    void protectedEndpointWithTamperedTokenReturns401() throws Exception {
        String validToken = login("admin@test.local", SEED_PASSWORD).as(LoginResponse.class).accessToken();
        String tampered = validToken.substring(0, validToken.length() - 4) + "abcd";

        ResponseEntity<String> response = getWithToken("/test/authz/admin-only", tampered);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"NO_AUTENTICADO\"");
    }

    // ---- Autorización por rol ----

    @Test
    void adminCanAccessAdminOnlyEndpoint() throws Exception {
        String token = login("admin@test.local", SEED_PASSWORD).as(LoginResponse.class).accessToken();

        ResponseEntity<String> response = getWithToken("/test/authz/admin-only", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void operatorCannotAccessAdminOnlyEndpoint() throws Exception {
        String token = login("operator@test.local", SEED_PASSWORD).as(LoginResponse.class).accessToken();

        ResponseEntity<String> response = getWithToken("/test/authz/admin-only", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"ROL_NO_AUTORIZADO\"");
    }

    // ---- Autorización por sucursal (BR-018) ----

    @Test
    void operatorCanAccessOwnBranchData() throws Exception {
        String token = login("operator@test.local", SEED_PASSWORD).as(LoginResponse.class).accessToken();

        ResponseEntity<String> response = getWithToken("/test/authz/branches/" + branchA.getId() + "/data", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void operatorCannotAccessOtherBranchData() throws Exception {
        String token = login("operator@test.local", SEED_PASSWORD).as(LoginResponse.class).accessToken();

        ResponseEntity<String> response = getWithToken("/test/authz/branches/" + branchB.getId() + "/data", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"SUCURSAL_NO_AUTORIZADA\"");
    }

    @Test
    void adminCanAccessAnyBranchDataRegardlessOfOwnBranch() throws Exception {
        String token = login("admin@test.local", SEED_PASSWORD).as(LoginResponse.class).accessToken();

        ResponseEntity<String> response = getWithToken("/test/authz/branches/" + branchB.getId() + "/data", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- /auth/me ----

    @Test
    void meReturnsAuthenticatedUserProfile() throws Exception {
        String token = login("manager@test.local", SEED_PASSWORD).as(LoginResponse.class).accessToken();

        ResponseEntity<UserSummaryResponse> response = restTemplate.exchange(
                "/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), UserSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().email()).isEqualTo("manager@test.local");
        assertThat(response.getBody().role()).isEqualTo("MANAGER");
        assertThat(response.getBody().branchId()).isEqualTo(String.valueOf(branchA.getId()));
    }

    // ---- helpers ----

    private LoginResult login(String email, String password) throws Exception {
        String requestBody = objectMapper.writeValueAsString(new LoginRequest(email, password));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(restTemplate.getRootUri() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new LoginResult(response.statusCode(), response.body(), objectMapper);
    }

    private ResponseEntity<String> getWithToken(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String buildRawToken(String subject, String role, Long branchId, Instant expiration) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder()
                .subject(subject)
                .claim("name", "Forjado")
                .claim("email", "forjado@test.local")
                .claim("role", role)
                .issuedAt(Date.from(expiration.minusSeconds(3600)))
                .expiration(Date.from(expiration))
                .signWith(key);
        if (branchId != null) {
            builder.claim("branchId", branchId);
        }
        return builder.compact();
    }

    private record LoginResult(int status, String rawBody, ObjectMapper objectMapper) {
        <T> T as(Class<T> type) throws Exception {
            return objectMapper.readValue(rawBody, type);
        }
    }
}
