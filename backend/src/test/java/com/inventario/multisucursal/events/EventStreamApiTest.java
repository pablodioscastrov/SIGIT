package com.inventario.multisucursal.events;

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
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El endpoint SSE a nivel HTTP: autenticación (incluida la excepción del
 * token por query string), rechazo de suscripción a sucursal ajena, y que
 * REST siga sirviendo como camino de reconciliación.
 *
 * <p>Se usa {@link HttpClient} del JDK y no {@code TestRestTemplate}: una
 * respuesta {@code text/event-stream} nunca termina, así que hay que leerla
 * en streaming y cerrarla a mano — {@code TestRestTemplate} se quedaría
 * bloqueado esperando el fin del cuerpo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class EventStreamApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventBroadcaster eventBroadcaster;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Branch branchA;
    private Branch branchB;
    private String adminToken;
    private String operatorAToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branchA = branchRepository.save(new Branch("SUC-SSE-A", "Sucursal A", null));
        branchB = branchRepository.save(new Branch("SUC-SSE-B", "Sucursal B", null));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Operador A", "operator.a@test.local", hash, RoleCode.OPERATOR, branchA.getId()));

        adminToken = login("admin@test.local");
        operatorAToken = login("operator.a@test.local");
    }

    @Test
    void streamRequiresAuthentication() throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(restTemplate.getRootUri() + "/api/v1/events"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void streamOpensWithBearerTokenAndAnnouncesTheSubscription() throws Exception {
        StreamResult result = openStream("/api/v1/events", "Bearer", adminToken);

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.contentType()).contains("text/event-stream");
        assertThat(result.firstChunk()).contains("stream.opened");
        assertThat(result.firstChunk())
                .as("se sugiere al navegador cuánto esperar antes de reconectar")
                .contains("retry:");
    }

    /** docs/API_DESIGN.md, sección 2: {@code EventSource} no puede fijar encabezados. */
    @Test
    void streamAlsoAcceptsTheTokenAsQueryParameterOnThisRouteOnly() throws Exception {
        StreamResult withQueryToken = openStream("/api/v1/events?access_token=" + adminToken, null, null);
        assertThat(withQueryToken.statusCode()).isEqualTo(200);

        // La excepción no se extiende al resto de la API: ahí el token debe ir en el encabezado.
        HttpResponse<String> restWithQueryToken = httpClient.send(
                HttpRequest.newBuilder(URI.create(restTemplate.getRootUri() + "/api/v1/branches?access_token=" + adminToken))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(restWithQueryToken.statusCode()).isEqualTo(401);
    }

    @Test
    void operatorCannotSubscribeToAForeignBranch() throws Exception {
        StreamResult foreign = openStream("/api/v1/events?branchId=" + branchB.getId(), "Bearer", operatorAToken);
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(foreign.firstChunk()).contains("SUCURSAL_NO_AUTORIZADA");

        StreamResult own = openStream("/api/v1/events?branchId=" + branchA.getId(), "Bearer", operatorAToken);
        assertThat(own.statusCode()).isEqualTo(200);
    }

    /**
     * Un servidor SSE solo descubre que el cliente se fue cuando intenta
     * escribirle: por eso la limpieza depende del latido, no del cierre en sí.
     * Aquí se invoca a mano para no esperar su intervalo real.
     */
    @Test
    void heartbeatReleasesTheSubscriptionOfAClientThatWentAway() throws Exception {
        int before = eventBroadcaster.activeSubscriptions();
        StreamResult result = openStream("/api/v1/events", "Bearer", adminToken);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(eventBroadcaster.activeSubscriptions()).isEqualTo(before + 1);

        awaitSubscriptionsAfterHeartbeat(before);

        assertThat(eventBroadcaster.activeSubscriptions())
                .as("la conexión abandonada no puede quedar ocupando memoria hasta el timeout")
                .isEqualTo(before);
    }

    /** Si el canal no está disponible, la aplicación sigue siendo usable por REST. */
    @Test
    void restRemainsTheFallbackWhenTheChannelIsNotUsed() {
        ResponseEntity<String> inventory = restTemplate.exchange("/api/v1/inventory", HttpMethod.GET,
                new HttpEntity<>(authHeaders(adminToken)), String.class);
        ResponseEntity<String> branches = restTemplate.exchange("/api/v1/branches", HttpMethod.GET,
                new HttpEntity<>(authHeaders(adminToken)), String.class);

        assertThat(inventory.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(branches.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(branches.getBody()).contains("SUC-SSE-A");
    }

    // ---- helpers ----

    private record StreamResult(int statusCode, String contentType, String firstChunk) {
    }

    /** Abre el stream, lee el primer fragmento y cierra — sin quedarse bloqueado. */
    private StreamResult openStream(String path, String scheme, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(restTemplate.getRootUri() + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (token != null) {
            builder.header("Authorization", scheme + " " + token);
        }
        HttpResponse<java.io.InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("content-type").orElse("");
        String chunk;
        try (java.io.InputStream body = response.body()) {
            byte[] buffer = new byte[512];
            int read = body.read(buffer);
            chunk = read > 0 ? new String(buffer, 0, read) : "";
        }
        return new StreamResult(response.statusCode(), contentType, chunk);
    }

    private void awaitSubscriptionsAfterHeartbeat(int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && eventBroadcaster.activeSubscriptions() != expected; attempt++) {
            eventBroadcaster.heartbeat();
            Thread.sleep(100);
        }
    }

    private String login(String email) {
        var body = new com.inventario.multisucursal.auth.LoginRequest(email, SEED_PASSWORD);
        return restTemplate.postForEntity("/api/v1/auth/login", body, com.inventario.multisucursal.auth.LoginResponse.class)
                .getBody().accessToken();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
