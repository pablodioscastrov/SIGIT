package com.inventario.multisucursal.transfers;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.inventory.InventoryAdjustmentRequest;
import com.inventario.multisucursal.inventory.InventoryMovement;
import com.inventario.multisucursal.inventory.InventoryMovementRepository;
import com.inventario.multisucursal.inventory.InventoryMovementService;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.inventory.MovementDirection;
import com.inventario.multisucursal.inventory.MovementReason;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.ProductUnit;
import com.inventario.multisucursal.products.ProductUnitRepository;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mismo escenario y mismas aserciones que {@link TransferDispatchConcurrencyTest}
 * (dos despachos reales de la misma transferencia), pero contra PostgreSQL
 * real vía Testcontainers — ver el javadoc de
 * {@link com.inventario.multisucursal.inventory.InventoryConcurrencyPostgresTest}
 * para la justificación general (docs/TEST_STRATEGY.md §4). Aquí importa en
 * particular porque el mecanismo bajo prueba es una transición de estado
 * atómica (UPDATE ... WHERE status = 'APPROVED'): el comportamiento exacto
 * de bloqueo de fila de Postgres ante dos UPDATE concurrentes sobre la misma
 * fila (bloquear y reevaluar el predicado, en vez de fallar de inmediato) es
 * justo lo que garantiza que el perdedor reciba 0 filas afectadas — no algo
 * que H2 esté obligado a replicar igual. {@code disabledWithoutDocker =
 * true}: se omite con gracia sin Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferDispatchConcurrencyPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TransferService transferService;

    @Autowired
    private InventoryMovementService inventoryMovementService;

    @Autowired
    private TransferItemRepository transferItemRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryMovementRepository movementRepository;

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
    private PasswordEncoder passwordEncoder;

    @Test
    void twoConcurrentDispatchesOfTheSameTransferOnlyOneSucceeds() throws Exception {
        Branch origin = branchRepository.save(new Branch("SUC-CCDIPG-O", "Origen Despacho Concurrente PG", null));
        Branch destination = branchRepository.save(new Branch("SUC-CCDIPG-D", "Destino Despacho Concurrente PG", null));
        User operator = userRepository.save(new User(
                "Operador CC Despacho PG", "operator.cc.dispatch.pg@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, origin.getId()));
        User admin = userRepository.save(new User(
                "Admin CC Despacho PG", "admin.cc.dispatch.pg@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.ADMIN, null));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCDIPG", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CCDIPG-001", "Producto Despacho Concurrente PG", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));

        AuthenticatedUser adminPrincipal = new AuthenticatedUser(admin.getId(), admin.getName(), admin.getEmail(), RoleCode.ADMIN, null);
        AuthenticatedUser operatorPrincipal =
                new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, origin.getId());

        Long transferId;
        Long itemId;
        authenticateAs(adminPrincipal);
        try {
            inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(origin.getId(), product.getId(), null, MovementDirection.INGRESO, null, BigDecimal.TEN, "Stock inicial"),
                    admin.getId());
            var transfer = transferService.request(
                    new CreateTransferRequest(origin.getId(), destination.getId(), false,
                            List.of(new CreateTransferItemRequest(product.getId(), new BigDecimal("6")))),
                    admin.getId(), UUID.randomUUID().toString());
            transferId = Long.valueOf(transfer.id());
            itemId = Long.valueOf(transfer.items().get(0).id());
            transferService.approve(transferId, new ApproveTransferRequest(
                    List.of(new ApproveTransferItemRequest(itemId, new BigDecimal("6")))), admin.getId());
        } finally {
            SecurityContextHolder.clearContext();
        }

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Exception>> futures = new ArrayList<>();
        Long finalTransferId = transferId;
        Long finalItemId = itemId;

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                authenticateAs(operatorPrincipal);
                try {
                    ready.countDown();
                    start.await();
                    transferService.dispatch(finalTransferId, new DispatchTransferRequest(
                            "Transportes CC", null, List.of(new DispatchTransferItemRequest(finalItemId, new BigDecimal("6")))),
                            operator.getId());
                    return null;
                } catch (Exception e) {
                    return e;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        int successes = 0;
        int conflicts = 0;
        for (Future<Exception> future : futures) {
            Exception outcome = future.get(20, TimeUnit.SECONDS);
            if (outcome == null) {
                successes++;
            } else {
                assertThat(outcome).isInstanceOf(ResourceConflictException.class);
                assertThat(((ResourceConflictException) outcome).getCode()).isEqualTo("TRANSICION_INVALIDA");
                conflicts++;
            }
        }
        executor.shutdown();

        assertThat(successes).as("exactamente un despacho debe confirmarse").isEqualTo(1);
        assertThat(conflicts).as("el otro debe rechazarse por transición inválida").isEqualTo(1);

        BigDecimal finalStock = inventoryRepository.findByProductIdAndBranchId(product.getId(), origin.getId())
                .orElseThrow().getQuantityOnHand();
        assertThat(finalStock).as("el stock de origen se descuenta exactamente una vez: 10 - 6 = 4").isEqualByComparingTo(new BigDecimal("4"));

        TransferItem finalItem = transferItemRepository.findById(itemId).orElseThrow();
        assertThat(finalItem.getQuantityShipped()).isEqualByComparingTo(new BigDecimal("6"));

        List<InventoryMovement> dispatchMovements = movementRepository.findAll().stream()
                .filter(m -> m.getReason() == MovementReason.TRANSFERENCIA_SALIDA && itemId.equals(m.getTransferItemId()))
                .toList();
        assertThat(dispatchMovements).as("exactamente un movimiento de salida para esta línea, nunca dos").hasSize(1);
        assertThat(dispatchMovements.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("6"));
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
