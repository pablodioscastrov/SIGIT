package com.inventario.multisucursal.events;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryAdjustmentRequest;
import com.inventario.multisucursal.inventory.InventoryMovementService;
import com.inventario.multisucursal.inventory.MovementDirection;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.ProductUnit;
import com.inventario.multisucursal.products.ProductUnitRepository;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Canal near-real-time (ADR-007). Se ejercita el reparto con emisores
 * simulados: montar un {@code EventSource} real requeriría un navegador, y lo
 * que hay que demostrar aquí es <b>a quién</b> llega cada señal, <b>cuándo</b>
 * se emite y que su fallo <b>no</b> afecta al negocio.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class EventBroadcastTest {

    @Autowired
    private EventBroadcaster eventBroadcaster;

    @Autowired
    private InventoryMovementService inventoryMovementService;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @Autowired
    private ProductUnitRepository productUnitRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    private Branch branchA;
    private Branch branchB;
    private AuthenticatedUser adminPrincipal;
    private AuthenticatedUser operatorA;
    private AuthenticatedUser operatorB;

    @BeforeEach
    void setUp() {
        branchA = branchRepository.save(new Branch(uniqueCode("EVA"), "Sucursal A", null));
        branchB = branchRepository.save(new Branch(uniqueCode("EVB"), "Sucursal B", null));
        adminPrincipal = new AuthenticatedUser(1L, "Admin", "admin@ev.local", RoleCode.ADMIN, null);
        operatorA = new AuthenticatedUser(2L, "Op A", "opa@ev.local", RoleCode.OPERATOR, branchA.getId());
        operatorB = new AuthenticatedUser(3L, "Op B", "opb@ev.local", RoleCode.OPERATOR, branchB.getId());
    }

    // ---- El cliente recibe la actualización relevante ----

    @Test
    void subscriberReceivesRelevantEvent() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        eventBroadcaster.register(operatorA, Set.of(), emitter);

        eventBroadcaster.broadcast(DomainEvent.inventoryUpdated(branchA.getId(), 77L));

        ArgumentCaptor<SseEmitter.SseEventBuilder> sent = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(sent.capture());

        EventPayload payload = sent.getValue().build().stream()
                .map(org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(EventPayload.class::isInstance)
                .map(EventPayload.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("El evento enviado no llevaba un EventPayload"));

        assertThat(payload.type()).isEqualTo(DomainEvent.INVENTORY_UPDATED);
        assertThat(payload.branchIds()).containsExactly(String.valueOf(branchA.getId()));
        assertThat(payload.resourceId())
                .as("el evento es una señal: lleva ids para reconsultar, no el stock resultante")
                .isEqualTo("77");
    }

    @Test
    void eventIsEmittedOnlyAfterTheTransactionCommits() throws IOException {
        // Se ejercita el flujo real de negocio, no el broadcaster directamente:
        // el ajuste publica dentro de su @Transactional y el envío ocurre al commit.
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure(uniqueCode("UE"), "Unidad"));
        Product product = productRepository.save(new Product(uniqueCode("SKU-EV"), "Producto Evento", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        User operator = userRepository.save(new User(
                "Op Ev", uniqueCode("op.ev") + "@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branchA.getId()));
        AuthenticatedUser principal = new AuthenticatedUser(
                operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branchA.getId());

        SseEmitter emitter = mock(SseEmitter.class);
        eventBroadcaster.register(principal, Set.of(), emitter);

        authenticateAs(principal);
        try {
            inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(branchA.getId(), product.getId(), null, MovementDirection.INGRESO, null,
                            BigDecimal.TEN, "Alta para probar el canal"),
                    operator.getId());
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void noEventIsEmittedWhenTheTransactionRollsBack() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        eventBroadcaster.register(operatorA, Set.of(), emitter);

        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure(uniqueCode("UR"), "Unidad"));
        Product product = productRepository.save(new Product(uniqueCode("SKU-RB"), "Producto", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        User operator = userRepository.save(new User(
                "Op RB", uniqueCode("op.rb") + "@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branchA.getId()));
        AuthenticatedUser principal = new AuthenticatedUser(
                operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branchA.getId());

        authenticateAs(principal);
        try {
            // Retiro sin stock: la transacción falla y revierte.
            assertThatCode(() -> inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(branchA.getId(), product.getId(), null, MovementDirection.RETIRO, null,
                            BigDecimal.TEN, "Retiro imposible"),
                    operator.getId()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ---- Aislamiento por sucursal ----

    @Test
    void subscriberDoesNotReceiveTransferEventsOfBranchesItCannotSee() throws IOException {
        SseEmitter foreignEmitter = mock(SseEmitter.class);
        SseEmitter involvedEmitter = mock(SseEmitter.class);
        SseEmitter adminEmitter = mock(SseEmitter.class);
        eventBroadcaster.register(operatorB, Set.of(), foreignEmitter);
        eventBroadcaster.register(operatorA, Set.of(), involvedEmitter);
        eventBroadcaster.register(adminPrincipal, Set.of(), adminEmitter);

        // Transferencia entre la sucursal A y una tercera: el operador de B no es parte.
        Branch thirdBranch = branchRepository.save(new Branch(uniqueCode("EVC"), "Sucursal C", null));
        eventBroadcaster.broadcast(DomainEvent.transferStatusChanged(500L, branchA.getId(), thirdBranch.getId()));

        verify(foreignEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(involvedEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(adminEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void inventorySignalsFollowTheSameOpenReadScopeAsRest() throws IOException {
        // RF-003: cualquier rol puede consultar el stock de cualquier sucursal por
        // REST, así que la señal tampoco revela nada nuevo — el canal concede
        // exactamente lo mismo que la API, ni más ni menos.
        SseEmitter otherBranchEmitter = mock(SseEmitter.class);
        eventBroadcaster.register(operatorB, Set.of(), otherBranchEmitter);

        eventBroadcaster.broadcast(DomainEvent.inventoryUpdated(branchA.getId(), 90L));

        verify(otherBranchEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void explicitBranchFilterNarrowsWhatTheClientReceives() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        eventBroadcaster.register(adminPrincipal, Set.of(branchB.getId()), emitter);

        eventBroadcaster.broadcast(DomainEvent.inventoryUpdated(branchA.getId(), 1L));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));

        eventBroadcaster.broadcast(DomainEvent.inventoryUpdated(branchB.getId(), 1L));
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ---- Resiliencia: el canal no puede romper el negocio ----

    @Test
    void aFailingSubscriberIsDroppedAndDoesNotBreakTheBusinessOperation() throws IOException {
        SseEmitter brokenEmitter = mock(SseEmitter.class);
        SseEmitter healthyEmitter = mock(SseEmitter.class);
        doThrow(new IOException("cliente desconectado")).when(brokenEmitter).send(any(SseEmitter.SseEventBuilder.class));
        eventBroadcaster.register(operatorA, Set.of(), brokenEmitter);
        eventBroadcaster.register(operatorA, Set.of(), healthyEmitter);
        int before = eventBroadcaster.activeSubscriptions();

        assertThatCode(() -> eventBroadcaster.broadcast(DomainEvent.inventoryUpdated(branchA.getId(), 5L)))
                .as("un cliente caído no puede propagar su error al hilo que ejecutó la operación")
                .doesNotThrowAnyException();

        verify(healthyEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(eventBroadcaster.activeSubscriptions())
                .as("la suscripción rota se descarta; la sana permanece")
                .isEqualTo(before - 1);
    }

    @Test
    void reconnectingClientGetsAFreshSubscriptionAndResumesReceiving() throws IOException {
        SseEmitter firstConnection = mock(SseEmitter.class);
        long firstId = eventBroadcaster.register(operatorA, Set.of(), firstConnection);
        int afterFirst = eventBroadcaster.activeSubscriptions();

        // El canal se cierra (timeout o corte de red): el registro lo suelta.
        eventBroadcaster.unregister(firstId);
        assertThat(eventBroadcaster.activeSubscriptions()).isEqualTo(afterFirst - 1);

        // Mientras está desconectado ocurre un cambio: esa señal se pierde, por diseño.
        eventBroadcaster.broadcast(DomainEvent.inventoryUpdated(branchA.getId(), 11L));
        verify(firstConnection, never()).send(any(SseEmitter.SseEventBuilder.class));

        // Al reconectar recibe los eventos siguientes; los perdidos los recupera
        // por REST, que es la fuente de verdad (no hay búfer de reproducción).
        SseEmitter reconnected = mock(SseEmitter.class);
        eventBroadcaster.register(operatorA, Set.of(), reconnected);
        eventBroadcaster.broadcast(DomainEvent.inventoryUpdated(branchA.getId(), 12L));

        verify(reconnected, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** Códigos cortos y únicos: {@code branch.code} admite 20 caracteres y {@code unit_of_measure.code} solo 10. */
    private static String uniqueCode(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
