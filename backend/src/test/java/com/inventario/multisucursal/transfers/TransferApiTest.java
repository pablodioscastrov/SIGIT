package com.inventario.multisucursal.transfers;

import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryRepository;
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
 * Ciclo completo de transferencia (flujos C–F de docs/CRITICAL_FLOWS.md):
 * happy path, transiciones inválidas, recepción parcial con tratamiento del
 * faltante, permisos por sucursal e idempotencia. La concurrencia real
 * (venta vs. despacho) vive en {@link TransferConcurrencyTest} y el rollback
 * en {@link TransferRollbackTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class TransferApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferItemRepository transferItemRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Branch origin;
    private Branch destination;
    private Branch thirdBranch;
    private UnitOfMeasure unUnit;
    private String adminToken;
    private String originManagerToken;
    private String originOperatorToken;
    private String destinationManagerToken;
    private String destinationOperatorToken;
    private String outsiderOperatorToken;

    @BeforeEach
    void setUp() {
        transferItemRepository.deleteAll();
        transferRepository.deleteAll();
        inventoryRepository.deleteAll();
        unitOfMeasureRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();

        origin = branchRepository.save(new Branch("SUC-ORI", "Sucursal Origen", null));
        destination = branchRepository.save(new Branch("SUC-DES", "Sucursal Destino", null));
        thirdBranch = branchRepository.save(new Branch("SUC-TER", "Sucursal Ajena", null));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        userRepository.save(new User("Gerente Origen", "manager.origin@test.local", hash, RoleCode.MANAGER, origin.getId()));
        userRepository.save(new User("Operador Origen", "operator.origin@test.local", hash, RoleCode.OPERATOR, origin.getId()));
        userRepository.save(new User("Gerente Destino", "manager.dest@test.local", hash, RoleCode.MANAGER, destination.getId()));
        userRepository.save(new User("Operador Destino", "operator.dest@test.local", hash, RoleCode.OPERATOR, destination.getId()));
        userRepository.save(new User("Operador Ajeno", "operator.third@test.local", hash, RoleCode.OPERATOR, thirdBranch.getId()));

        adminToken = login("admin@test.local");
        originManagerToken = login("manager.origin@test.local");
        originOperatorToken = login("operator.origin@test.local");
        destinationManagerToken = login("manager.dest@test.local");
        destinationOperatorToken = login("operator.dest@test.local");
        outsiderOperatorToken = login("operator.third@test.local");

        unUnit = unitOfMeasureRepository.save(new UnitOfMeasure("UN", "Unidad"));
    }

    // ---- Happy path completo ----

    @Test
    void fullHappyPathRequestApproveDispatchReceiveComplete() {
        String productId = createProduct("SKU-TR-001");
        stockUp(productId, origin.getId(), 100);

        TransferResponse requested = request(productId, 30).getBody();
        assertThat(requested.status()).isEqualTo(TransferStatus.REQUESTED);
        assertThat(requested.transferNumber()).startsWith("TR-");
        assertThat(requested.items()).hasSize(1);
        assertThat(requested.items().get(0).quantityRequested()).isEqualByComparingTo(new BigDecimal("30"));

        String transferId = requested.id();
        String itemId = requested.items().get(0).id();

        // C2: aprobar ajustando la cantidad hacia abajo.
        TransferResponse approved = approve(transferId, itemId, 20, originManagerToken, TransferResponse.class).getBody();
        assertThat(approved.status()).isEqualTo(TransferStatus.APPROVED);
        assertThat(approved.items().get(0).quantityApproved()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(approved.approvedByUserId()).isNotNull();
        // Aprobar NO reserva stock (decisión de diseño del flujo C2).
        assertThat(stockOf(productId, origin.getId())).isEqualByComparingTo(new BigDecimal("100"));

        // D: despachar descuenta el stock del origen.
        TransferResponse dispatched = dispatch(transferId, itemId, 20, originOperatorToken, TransferResponse.class).getBody();
        assertThat(dispatched.status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(dispatched.carrierName()).isEqualTo("Transportes XYZ");
        assertThat(dispatched.estimatedArrivalDate()).isNotNull();
        assertThat(dispatched.dispatchedAt()).isNotNull();
        assertThat(stockOf(productId, origin.getId())).isEqualByComparingTo(new BigDecimal("80"));
        assertThat(inventoryRepository.findByProductIdAndBranchId(Long.valueOf(productId), destination.getId())).isEmpty();

        // E: recepción completa incrementa el stock del destino.
        TransferResponse received = receive(transferId, itemId, 20, destinationOperatorToken, TransferResponse.class).getBody();
        assertThat(received.status()).isEqualTo(TransferStatus.RECEIVED_COMPLETE);
        assertThat(received.receivedAt()).isNotNull();
        assertThat(received.items().get(0).quantityReceived()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(received.items().get(0).quantityMissing()).isNull();
        assertThat(stockOf(productId, destination.getId())).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(stockOf(productId, origin.getId())).isEqualByComparingTo(new BigDecimal("80"));
    }

    @Test
    void movementsAreGeneratedOnBothBranchesAndLinkedToTheLine() {
        String productId = createProduct("SKU-TR-002");
        stockUp(productId, origin.getId(), 50);
        TransferResponse transfer = request(productId, 10).getBody();
        String transferId = transfer.id();
        String itemId = transfer.items().get(0).id();
        approve(transferId, itemId, 10, originManagerToken, TransferResponse.class);
        dispatch(transferId, itemId, 10, originOperatorToken, TransferResponse.class);
        receive(transferId, itemId, 10, destinationOperatorToken, TransferResponse.class);

        String outbound = getWithToken(
                "/api/v1/inventory-movements?branchId=" + origin.getId() + "&productId=" + productId + "&reason=TRANSFERENCIA_SALIDA",
                adminToken, String.class).getBody();
        assertThat(outbound).contains("\"direction\":\"RETIRO\"").contains("\"type\":\"TRANSFER\"").contains("\"id\":\"" + itemId + "\"");

        String inbound = getWithToken(
                "/api/v1/inventory-movements?branchId=" + destination.getId() + "&productId=" + productId + "&reason=TRANSFERENCIA_ENTRADA",
                adminToken, String.class).getBody();
        assertThat(inbound).contains("\"direction\":\"INGRESO\"").contains("\"type\":\"TRANSFER\"").contains("\"id\":\"" + itemId + "\"");
    }

    // ---- Solicitud: validaciones ----

    @Test
    void sameOriginAndDestinationIsRejected() {
        String productId = createProduct("SKU-TR-003");
        ResponseEntity<String> response = post("/api/v1/transfers", Map.of(
                "originBranchId", destination.getId(), "destinationBranchId", destination.getId(),
                "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityRequested", 5))),
                destinationOperatorToken, String.class, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"ORIGEN_IGUAL_DESTINO\"");
    }

    @Test
    void nonPositiveRequestedQuantityIsRejected() {
        String productId = createProduct("SKU-TR-004");
        ResponseEntity<String> response = requestRaw(productId, 0, destinationOperatorToken, String.class, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_INVALIDA\"");
    }

    @Test
    void inactiveProductIsRejected() {
        String productId = createProduct("SKU-TR-005");
        restTemplate.exchange("/api/v1/products/" + productId + "/deactivate", HttpMethod.POST,
                new HttpEntity<>(null, authHeaders(originOperatorToken)), ProductResponse.class);

        ResponseEntity<String> response = requestRaw(productId, 5, destinationOperatorToken, String.class, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"PRODUCTO_INACTIVO\"");
    }

    @Test
    void retryingRequestWithSameIdempotencyKeyDoesNotCreateASecondTransfer() {
        String productId = createProduct("SKU-TR-006");
        String key = UUID.randomUUID().toString();

        TransferResponse first = requestRaw(productId, 5, destinationOperatorToken, TransferResponse.class, key).getBody();
        TransferResponse retry = requestRaw(productId, 5, destinationOperatorToken, TransferResponse.class, key).getBody();

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(transferRepository.count()).isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyOnRequestIsRejected() {
        String productId = createProduct("SKU-TR-007");
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "originBranchId", origin.getId(), "destinationBranchId", destination.getId(),
                        "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityRequested", 5))),
                        authHeaders(destinationOperatorToken)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"IDEMPOTENCY_KEY_REQUERIDO\"");
    }

    // ---- Aprobación ----

    @Test
    void approvingMoreThanRequestedIsRejected() {
        String productId = createProduct("SKU-TR-008");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = request(productId, 10).getBody();

        ResponseEntity<String> response = approve(transfer.id(), transfer.items().get(0).id(), 11, originManagerToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_APROBADA_EXCEDE_SOLICITADO\"");
    }

    @Test
    void approvingMoreThanAvailableStockIsRejected() {
        String productId = createProduct("SKU-TR-009");
        stockUp(productId, origin.getId(), 5);
        TransferResponse transfer = request(productId, 10).getBody();

        ResponseEntity<String> response = approve(transfer.id(), transfer.items().get(0).id(), 10, originManagerToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"STOCK_INSUFICIENTE_PARA_TRANSFERENCIA\"");
    }

    @Test
    void doubleApprovalIsRejected() {
        String productId = createProduct("SKU-TR-010");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = request(productId, 10).getBody();
        approve(transfer.id(), transfer.items().get(0).id(), 10, originManagerToken, TransferResponse.class);

        ResponseEntity<String> second = approve(transfer.id(), transfer.items().get(0).id(), 10, originManagerToken, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("\"code\":\"TRANSICION_INVALIDA\"");
    }

    @Test
    void approvalMustIncludeEveryLine() {
        String productA = createProduct("SKU-TR-011A");
        String productB = createProduct("SKU-TR-011B");
        stockUp(productA, origin.getId(), 50);
        stockUp(productB, origin.getId(), 50);
        TransferResponse transfer = requestTwoLines(productA, productB);

        ResponseEntity<String> response = approve(transfer.id(), transfer.items().get(0).id(), 5, originManagerToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"APROBACION_INCOMPLETA\"");
    }

    @Test
    void rejectedTransferCannotBeApprovedOrDispatched() {
        String productId = createProduct("SKU-TR-012");
        stockUp(productId, origin.getId(), 50);
        TransferResponse transfer = request(productId, 10).getBody();

        TransferResponse rejected = postAction("/api/v1/transfers/" + transfer.id() + "/reject", originManagerToken, TransferResponse.class).getBody();
        assertThat(rejected.status()).isEqualTo(TransferStatus.REJECTED);

        assertThat(approve(transfer.id(), transfer.items().get(0).id(), 10, originManagerToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dispatch(transfer.id(), transfer.items().get(0).id(), 10, originOperatorToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- Despacho ----

    @Test
    void dispatchingWithoutApprovalIsRejected() {
        String productId = createProduct("SKU-TR-013");
        stockUp(productId, origin.getId(), 50);
        TransferResponse transfer = request(productId, 10).getBody();

        ResponseEntity<String> response = dispatch(transfer.id(), transfer.items().get(0).id(), 10, originOperatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"TRANSICION_INVALIDA\"");
    }

    @Test
    void dispatchingMoreThanApprovedIsRejected() {
        String productId = createProduct("SKU-TR-014");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = request(productId, 20).getBody();
        approve(transfer.id(), transfer.items().get(0).id(), 15, originManagerToken, TransferResponse.class);

        ResponseEntity<String> response = dispatch(transfer.id(), transfer.items().get(0).id(), 16, originOperatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"CANTIDAD_DESPACHO_EXCEDE_APROBADO\"");
        assertThat(stockOf(productId, origin.getId())).isEqualByComparingTo(new BigDecimal("100"));
    }

    /** Escenario 3.2: el stock aprobado se consumió por una venta antes del despacho. */
    @Test
    void dispatchIsRejectedWhenStockWasConsumedAfterApproval() {
        String productId = createProduct("SKU-TR-015");
        stockUp(productId, origin.getId(), 20);
        TransferResponse transfer = request(productId, 20).getBody();
        approve(transfer.id(), transfer.items().get(0).id(), 20, originManagerToken, TransferResponse.class);

        // El stock se consume entre aprobar y despachar (aquí, con un ajuste de retiro).
        withdraw(productId, origin.getId(), 6);

        ResponseEntity<String> response = dispatch(transfer.id(), transfer.items().get(0).id(), 20, originOperatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"STOCK_INSUFICIENTE\"");
        assertThat(stockOf(productId, origin.getId())).isEqualByComparingTo(new BigDecimal("14"));
    }

    @Test
    void doubleDispatchIsRejectedAndStockIsDiscountedOnlyOnce() {
        String productId = createProduct("SKU-TR-016");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = request(productId, 10).getBody();
        approve(transfer.id(), transfer.items().get(0).id(), 10, originManagerToken, TransferResponse.class);
        dispatch(transfer.id(), transfer.items().get(0).id(), 10, originOperatorToken, TransferResponse.class);

        ResponseEntity<String> second = dispatch(transfer.id(), transfer.items().get(0).id(), 10, originOperatorToken, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("\"code\":\"TRANSICION_INVALIDA\"");
        assertThat(stockOf(productId, origin.getId())).isEqualByComparingTo(new BigDecimal("90"));
    }

    @Test
    void dispatchMustIncludeEveryLine() {
        String productA = createProduct("SKU-TR-017A");
        String productB = createProduct("SKU-TR-017B");
        stockUp(productA, origin.getId(), 50);
        stockUp(productB, origin.getId(), 50);
        TransferResponse transfer = requestTwoLines(productA, productB);
        approveAll(transfer, 5);

        ResponseEntity<String> response = dispatch(transfer.id(), transfer.items().get(0).id(), 5, originOperatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"DESPACHO_INCOMPLETO\"");
    }

    // ---- Recepción ----

    @Test
    void receivingMoreThanShippedIsRejected() {
        String productId = createProduct("SKU-TR-018");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = inTransit(productId, 10, 10);

        ResponseEntity<String> response = receive(transfer.id(), transfer.items().get(0).id(), 11, destinationOperatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"RECEPCION_EXCEDE_ENVIADO\"");
    }

    @Test
    void doubleReceiveIsRejectedAndStockIsIncrementedOnlyOnce() {
        String productId = createProduct("SKU-TR-019");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = inTransit(productId, 10, 10);
        receive(transfer.id(), transfer.items().get(0).id(), 10, destinationOperatorToken, TransferResponse.class);

        ResponseEntity<String> second = receive(transfer.id(), transfer.items().get(0).id(), 10, destinationOperatorToken, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stockOf(productId, destination.getId())).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void receivingBeforeDispatchIsRejected() {
        String productId = createProduct("SKU-TR-020");
        stockUp(productId, origin.getId(), 50);
        TransferResponse transfer = request(productId, 10).getBody();
        approve(transfer.id(), transfer.items().get(0).id(), 10, originManagerToken, TransferResponse.class);

        ResponseEntity<String> response = receive(transfer.id(), transfer.items().get(0).id(), 10, destinationOperatorToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"TRANSICION_INVALIDA\"");
    }

    // ---- Recepción parcial + tratamiento del faltante ----

    @Test
    void partialReceiptRecordsShortageAndOnlyAddsWhatArrived() {
        String productId = createProduct("SKU-TR-021");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = inTransit(productId, 50, 50);

        TransferResponse received = receive(transfer.id(), transfer.items().get(0).id(), 45, destinationOperatorToken, TransferResponse.class).getBody();

        assertThat(received.status()).isEqualTo(TransferStatus.RECEIVED_PARTIAL);
        assertThat(received.items().get(0).quantityReceived()).isEqualByComparingTo(new BigDecimal("45"));
        assertThat(received.items().get(0).quantityMissing()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(received.items().get(0).discrepancyTreatment()).isNull();
        assertThat(stockOf(productId, destination.getId())).isEqualByComparingTo(new BigDecimal("45"));
    }

    @Test
    void receivingZeroIsAcceptedAsFullShortageWithoutMovement() {
        String productId = createProduct("SKU-TR-022");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = inTransit(productId, 10, 10);

        TransferResponse received = receive(transfer.id(), transfer.items().get(0).id(), 0, destinationOperatorToken, TransferResponse.class).getBody();

        assertThat(received.status()).isEqualTo(TransferStatus.RECEIVED_PARTIAL);
        assertThat(received.items().get(0).quantityMissing()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(inventoryRepository.findByProductIdAndBranchId(Long.valueOf(productId), destination.getId())).isEmpty();
    }

    @Test
    void treatmentReenvioCreatesFollowUpTransferAndClosesTheOriginal() {
        String productId = createProduct("SKU-TR-023");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = inTransit(productId, 50, 50);
        TransferResponse received = receive(transfer.id(), transfer.items().get(0).id(), 45, destinationOperatorToken, TransferResponse.class).getBody();
        String itemId = received.items().get(0).id();

        ResponseEntity<DiscrepancyTreatmentResponse> response = treat(transfer.id(), itemId, "REENVIO", destinationManagerToken, DiscrepancyTreatmentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().discrepancyTreatment()).isEqualTo(DiscrepancyTreatment.REENVIO);
        assertThat(response.getBody().followUpTransferId()).isNotNull();
        assertThat(response.getBody().transferStatus()).isEqualTo(TransferStatus.CLOSED);

        TransferResponse followUp = getWithToken("/api/v1/transfers/" + response.getBody().followUpTransferId(), adminToken, TransferResponse.class).getBody();
        assertThat(followUp.status()).isEqualTo(TransferStatus.REQUESTED);
        assertThat(followUp.originBranchId()).isEqualTo(String.valueOf(origin.getId()));
        assertThat(followUp.destinationBranchId()).isEqualTo(String.valueOf(destination.getId()));
        assertThat(followUp.items().get(0).quantityRequested()).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    void treatmentAjusteClosesWithoutFollowUp() {
        String productId = createProduct("SKU-TR-024");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = inTransit(productId, 20, 20);
        TransferResponse received = receive(transfer.id(), transfer.items().get(0).id(), 18, destinationOperatorToken, TransferResponse.class).getBody();

        DiscrepancyTreatmentResponse response = treat(
                transfer.id(), received.items().get(0).id(), "AJUSTE", destinationManagerToken, DiscrepancyTreatmentResponse.class).getBody();

        assertThat(response.followUpTransferId()).isNull();
        assertThat(response.transferStatus()).isEqualTo(TransferStatus.CLOSED);
    }

    @Test
    void doubleTreatmentIsRejected() {
        String productId = createProduct("SKU-TR-025");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = inTransit(productId, 20, 20);
        TransferResponse received = receive(transfer.id(), transfer.items().get(0).id(), 18, destinationOperatorToken, TransferResponse.class).getBody();
        String itemId = received.items().get(0).id();
        treat(transfer.id(), itemId, "AJUSTE", destinationManagerToken, DiscrepancyTreatmentResponse.class);

        ResponseEntity<String> second = treat(transfer.id(), itemId, "RECLAMACION", destinationManagerToken, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("\"code\":\"FALTANTE_YA_TRATADO\"");
    }

    @Test
    void treatingALineWithoutShortageIsRejected() {
        String productId = createProduct("SKU-TR-026");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = inTransit(productId, 10, 10);
        TransferResponse received = receive(transfer.id(), transfer.items().get(0).id(), 10, destinationOperatorToken, TransferResponse.class).getBody();

        ResponseEntity<String> response = treat(transfer.id(), received.items().get(0).id(), "AJUSTE", destinationManagerToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"LINEA_SIN_FALTANTE\"");
    }

    /** Escenario 3.5: tratar una línea no cierra la transferencia si otra sigue pendiente. */
    @Test
    void transferClosesOnlyWhenEveryShortageHasBeenTreated() {
        String productA = createProduct("SKU-TR-027A");
        String productB = createProduct("SKU-TR-027B");
        stockUp(productA, origin.getId(), 50);
        stockUp(productB, origin.getId(), 50);
        TransferResponse transfer = requestTwoLines(productA, productB);
        approveAll(transfer, 10);
        dispatchAll(transfer, 10);

        String transferId = transfer.id();
        String itemA = transfer.items().get(0).id();
        String itemB = transfer.items().get(1).id();

        // Ambas líneas llegan incompletas, en dos recepciones separadas.
        TransferResponse afterFirst = receive(transferId, itemA, 8, destinationOperatorToken, TransferResponse.class).getBody();
        assertThat(afterFirst.status()).as("con una línea aún sin recibir, la transferencia sigue en tránsito")
                .isEqualTo(TransferStatus.IN_TRANSIT);

        TransferResponse afterSecond = receive(transferId, itemB, 7, destinationOperatorToken, TransferResponse.class).getBody();
        assertThat(afterSecond.status()).isEqualTo(TransferStatus.RECEIVED_PARTIAL);

        DiscrepancyTreatmentResponse firstTreatment = treat(transferId, itemA, "AJUSTE", destinationManagerToken, DiscrepancyTreatmentResponse.class).getBody();
        assertThat(firstTreatment.transferStatus()).as("queda una línea sin tratar").isEqualTo(TransferStatus.RECEIVED_PARTIAL);

        DiscrepancyTreatmentResponse secondTreatment = treat(transferId, itemB, "AJUSTE", destinationManagerToken, DiscrepancyTreatmentResponse.class).getBody();
        assertThat(secondTreatment.transferStatus()).isEqualTo(TransferStatus.CLOSED);
    }

    // ---- Permisos y sucursal ----

    @Test
    void outsiderBranchCannotRequestApproveDispatchOrReceive() {
        String productId = createProduct("SKU-TR-028");
        stockUp(productId, origin.getId(), 100);

        // Solicitar en nombre de una sucursal destino que no es la suya.
        assertThat(requestRaw(productId, 5, outsiderOperatorToken, String.class, UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        TransferResponse transfer = request(productId, 10).getBody();
        assertThat(approve(transfer.id(), transfer.items().get(0).id(), 10, destinationManagerToken, String.class).getStatusCode())
                .as("el gerente del destino no puede aprobar: la aprobación es de la sucursal origen")
                .isEqualTo(HttpStatus.FORBIDDEN);

        approve(transfer.id(), transfer.items().get(0).id(), 10, originManagerToken, TransferResponse.class);
        assertThat(dispatch(transfer.id(), transfer.items().get(0).id(), 10, destinationOperatorToken, String.class).getStatusCode())
                .as("despachar es de la sucursal origen")
                .isEqualTo(HttpStatus.FORBIDDEN);

        dispatch(transfer.id(), transfer.items().get(0).id(), 10, originOperatorToken, TransferResponse.class);
        assertThat(receive(transfer.id(), transfer.items().get(0).id(), 10, originOperatorToken, String.class).getStatusCode())
                .as("recibir es de la sucursal destino")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void operatorCannotApproveButManagerCanDispatchAndReceive() {
        // La aprobación sigue siendo exclusiva de MANAGER/ADMIN; despachar y
        // recibir ahora también las puede ejecutar un MANAGER (ampliación de
        // permisos), no solo OPERATOR/ADMIN.
        String productId = createProduct("SKU-TR-029");
        stockUp(productId, origin.getId(), 100);
        TransferResponse transfer = request(productId, 10).getBody();

        ResponseEntity<String> operatorApproving = approve(transfer.id(), transfer.items().get(0).id(), 10, originOperatorToken, String.class);
        assertThat(operatorApproving.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(operatorApproving.getBody()).contains("\"code\":\"ROL_NO_AUTORIZADO\"");

        approve(transfer.id(), transfer.items().get(0).id(), 10, originManagerToken, TransferResponse.class);
        ResponseEntity<TransferResponse> managerDispatching =
                dispatch(transfer.id(), transfer.items().get(0).id(), 10, originManagerToken, TransferResponse.class);
        assertThat(managerDispatching.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<TransferResponse> managerReceiving =
                receive(transfer.id(), transfer.items().get(0).id(), 10, destinationManagerToken, TransferResponse.class);
        assertThat(managerReceiving.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void transferIsVisibleFromBothBranchesButNotFromAThirdOne() {
        String productId = createProduct("SKU-TR-030");
        stockUp(productId, origin.getId(), 50);
        TransferResponse transfer = request(productId, 5).getBody();

        assertThat(getWithToken("/api/v1/transfers/" + transfer.id(), originOperatorToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(getWithToken("/api/v1/transfers/" + transfer.id(), destinationOperatorToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(getWithToken("/api/v1/transfers/" + transfer.id(), outsiderOperatorToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getWithToken("/api/v1/transfers/" + transfer.id(), adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void listOnlyShowsTransfersOfTheOwnBranchForNonAdmin() {
        String productId = createProduct("SKU-TR-031");
        stockUp(productId, origin.getId(), 50);
        request(productId, 5);

        assertThat(getWithToken("/api/v1/transfers", originOperatorToken, String.class).getBody())
                .contains("\"originBranchId\":\"" + origin.getId() + "\"");
        assertThat(getWithToken("/api/v1/transfers", outsiderOperatorToken, String.class).getBody())
                .contains("\"totalElements\":0");
    }

    @Test
    void operationsOnNonexistentTransferReturn404() {
        assertThat(getWithToken("/api/v1/transfers/999999", adminToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private String createProduct(String sku) {
        return restTemplate.exchange("/api/v1/products", HttpMethod.POST,
                new HttpEntity<>(Map.of("sku", sku, "name", "Producto " + sku, "baseUnitOfMeasureId", unUnit.getId(), "minimumStock", 0, "unitPrice", 10),
                        authHeaders(originOperatorToken)),
                ProductResponse.class).getBody().id();
    }

    private void stockUp(String productId, Long branchId, int quantity) {
        restTemplate.exchange("/api/v1/inventory/adjustments", HttpMethod.POST,
                new HttpEntity<>(Map.of("branchId", branchId, "productId", Long.valueOf(productId),
                        "direction", "INGRESO", "quantity", quantity, "notes", "Carga inicial de prueba"),
                        authHeaders(adminToken)),
                Object.class);
    }

    private void withdraw(String productId, Long branchId, int quantity) {
        restTemplate.exchange("/api/v1/inventory/adjustments", HttpMethod.POST,
                new HttpEntity<>(Map.of("branchId", branchId, "productId", Long.valueOf(productId),
                        "direction", "RETIRO", "quantity", quantity, "notes", "Consumo entre aprobación y despacho"),
                        authHeaders(adminToken)),
                Object.class);
    }

    private BigDecimal stockOf(String productId, Long branchId) {
        return inventoryRepository.findByProductIdAndBranchId(Long.valueOf(productId), branchId)
                .orElseThrow(() -> new AssertionError("Sin inventario para producto=" + productId + " sucursal=" + branchId))
                .getQuantityOnHand();
    }

    private ResponseEntity<TransferResponse> request(String productId, int quantity) {
        return requestRaw(productId, quantity, destinationOperatorToken, TransferResponse.class, UUID.randomUUID().toString());
    }

    private <T> ResponseEntity<T> requestRaw(String productId, int quantity, String token, Class<T> responseType, String idempotencyKey) {
        return post("/api/v1/transfers", Map.of(
                "originBranchId", origin.getId(), "destinationBranchId", destination.getId(),
                "items", List.of(Map.of("productId", Long.valueOf(productId), "quantityRequested", quantity))),
                token, responseType, idempotencyKey);
    }

    private TransferResponse requestTwoLines(String productA, String productB) {
        return post("/api/v1/transfers", Map.of(
                "originBranchId", origin.getId(), "destinationBranchId", destination.getId(),
                "items", List.of(
                        Map.of("productId", Long.valueOf(productA), "quantityRequested", 10),
                        Map.of("productId", Long.valueOf(productB), "quantityRequested", 10))),
                destinationOperatorToken, TransferResponse.class, UUID.randomUUID().toString()).getBody();
    }

    private <T> ResponseEntity<T> approve(String transferId, String itemId, int quantity, String token, Class<T> responseType) {
        return post("/api/v1/transfers/" + transferId + "/approve",
                Map.of("items", List.of(Map.of("transferItemId", Long.valueOf(itemId), "quantityApproved", quantity))),
                token, responseType, null);
    }

    private void approveAll(TransferResponse transfer, int quantityPerLine) {
        post("/api/v1/transfers/" + transfer.id() + "/approve",
                Map.of("items", transfer.items().stream()
                        .map(item -> Map.<String, Object>of("transferItemId", Long.valueOf(item.id()), "quantityApproved", quantityPerLine))
                        .toList()),
                originManagerToken, TransferResponse.class, null);
    }

    private <T> ResponseEntity<T> dispatch(String transferId, String itemId, int quantity, String token, Class<T> responseType) {
        return post("/api/v1/transfers/" + transferId + "/dispatch",
                Map.of("carrierName", "Transportes XYZ", "estimatedArrivalDate", "2026-12-31",
                        "items", List.of(Map.of("transferItemId", Long.valueOf(itemId), "quantityShipped", quantity))),
                token, responseType, null);
    }

    private void dispatchAll(TransferResponse transfer, int quantityPerLine) {
        post("/api/v1/transfers/" + transfer.id() + "/dispatch",
                Map.of("carrierName", "Transportes XYZ", "estimatedArrivalDate", "2026-12-31",
                        "items", transfer.items().stream()
                                .map(item -> Map.<String, Object>of("transferItemId", Long.valueOf(item.id()), "quantityShipped", quantityPerLine))
                                .toList()),
                originOperatorToken, TransferResponse.class, null);
    }

    private <T> ResponseEntity<T> receive(String transferId, String itemId, int quantity, String token, Class<T> responseType) {
        return post("/api/v1/transfers/" + transferId + "/receive",
                Map.of("items", List.of(Map.of("transferItemId", Long.valueOf(itemId), "quantityReceived", quantity))),
                token, responseType, null);
    }

    private <T> ResponseEntity<T> treat(String transferId, String itemId, String treatment, String token, Class<T> responseType) {
        return post("/api/v1/transfers/" + transferId + "/items/" + itemId + "/discrepancy-treatment",
                Map.of("treatment", treatment, "notes", "Prueba automatizada"), token, responseType, null);
    }

    /** Atajo: deja una transferencia de una línea en IN_TRANSIT. */
    private TransferResponse inTransit(String productId, int requested, int shipped) {
        TransferResponse transfer = request(productId, requested).getBody();
        approve(transfer.id(), transfer.items().get(0).id(), shipped, originManagerToken, TransferResponse.class);
        return dispatch(transfer.id(), transfer.items().get(0).id(), shipped, originOperatorToken, TransferResponse.class).getBody();
    }

    private <T> ResponseEntity<T> post(String path, Object body, String token, Class<T> responseType, String idempotencyKey) {
        HttpHeaders headers = authHeaders(token);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
    }

    private <T> ResponseEntity<T> postAction(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(null, authHeaders(token)), responseType);
    }

    private <T> ResponseEntity<T> getWithToken(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), responseType);
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
