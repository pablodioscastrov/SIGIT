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
 * Mismo escenario y mismas aserciones que {@link TransferReceiveConcurrencyTest}
 * (dos recepciones reales de la misma línea de transferencia), pero contra
 * PostgreSQL real vía Testcontainers — ver el javadoc de
 * {@link com.inventario.multisucursal.inventory.InventoryConcurrencyPostgresTest}
 * (docs/TEST_STRATEGY.md §4). {@code disabledWithoutDocker = true}: se omite
 * con gracia sin Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferReceiveConcurrencyPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TransferService transferService;

    @Autowired
    private InventoryMovementService inventoryMovementService;

    @Autowired
    private TransferRepository transferRepository;

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
    void twoConcurrentReceivesOfTheSameTransferOnlyOneSucceeds() throws Exception {
        Branch origin = branchRepository.save(new Branch("SUC-CCREPG-O", "Origen Recepción Concurrente PG", null));
        Branch destination = branchRepository.save(new Branch("SUC-CCREPG-D", "Destino Recepción Concurrente PG", null));
        User operator = userRepository.save(new User(
                "Operador CC Recepción TR PG", "operator.cc.receive.tr.pg@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, destination.getId()));
        User admin = userRepository.save(new User(
                "Admin CC Recepción TR PG", "admin.cc.receive.tr.pg@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.ADMIN, null));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCREPG", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CCREPG-001", "Producto Recepción Concurrente PG", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));

        AuthenticatedUser adminPrincipal = new AuthenticatedUser(admin.getId(), admin.getName(), admin.getEmail(), RoleCode.ADMIN, null);
        AuthenticatedUser operatorPrincipal =
                new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, destination.getId());

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
            transferService.dispatch(transferId, new DispatchTransferRequest(
                    "Transportes CC", null, List.of(new DispatchTransferItemRequest(itemId, new BigDecimal("6")))), admin.getId());
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
                    transferService.receive(finalTransferId, new ReceiveTransferRequest(
                            List.of(new ReceiveTransferItemRequest(finalItemId, new BigDecimal("6")))), operator.getId());
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
                assertThat(((ResourceConflictException) outcome).getCode()).isEqualTo("RECEPCION_YA_REGISTRADA");
                conflicts++;
            }
        }
        executor.shutdown();

        assertThat(successes).as("exactamente una recepción debe confirmarse").isEqualTo(1);
        assertThat(conflicts).as("la otra debe rechazarse: la línea ya tenía recepción registrada").isEqualTo(1);

        BigDecimal finalStock = inventoryRepository.findByProductIdAndBranchId(product.getId(), destination.getId())
                .orElseThrow().getQuantityOnHand();
        assertThat(finalStock).as("el stock de destino queda en exactamente lo recibido una vez: 6, nunca 12").isEqualByComparingTo(new BigDecimal("6"));

        TransferItem finalItem = transferItemRepository.findById(itemId).orElseThrow();
        assertThat(finalItem.getQuantityReceived()).isEqualByComparingTo(new BigDecimal("6"));

        Transfer finalTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(finalTransfer.getStatus())
                .as("la transferencia cierra en RECEIVED_COMPLETE, no queda huérfana en IN_TRANSIT")
                .isEqualTo(TransferStatus.RECEIVED_COMPLETE);

        List<InventoryMovement> receiptMovements = movementRepository.findAll().stream()
                .filter(m -> m.getReason() == MovementReason.TRANSFERENCIA_ENTRADA && itemId.equals(m.getTransferItemId()))
                .toList();
        assertThat(receiptMovements).as("exactamente un movimiento de entrada para esta línea, nunca dos").hasSize(1);
        assertThat(receiptMovements.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("6"));
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
