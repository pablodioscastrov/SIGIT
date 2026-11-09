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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
 * Escenario 5 del encargo de confiabilidad/concurrencia: dos recepciones
 * reales (hilos independientes) de la <b>misma línea</b> de una
 * transferencia en tránsito. {@code TransferApiTest.doubleReceiveIsRejectedAndStockIsIncrementedOnlyOnce}
 * ya prueba el mismo resultado con dos llamadas HTTP secuenciales — no
 * ejercita la carrera real. Esta sí.
 *
 * <p><b>Mecanismo bajo prueba:</b> {@code TransferService.receive(...)}
 * aplica, por cada línea, {@code TransferItemRepository.markReceived(...)}
 * ({@code UPDATE ... WHERE quantity_received IS NULL}) <i>antes</i> de
 * incrementar {@code Inventory} — el perdedor de la carrera en esa línea
 * nunca debería llegar a aplicar el ingreso de stock.
 *
 * <p><b>Estado inicial:</b> transferencia {@code IN_TRANSIT} con una línea
 * despachada por 6; stock de destino = 0 (nunca tuvo el producto antes).
 * <p><b>Hilos:</b> 2, ambos reciben la misma línea con cantidad 6 (recepción
 * completa, sin faltante).
 * <p><b>Barrera:</b> {@link CountDownLatch} doble (ready/start).
 * <p><b>Resultado permitido:</b> exactamente una recepción se confirma; la
 * otra se rechaza con 409 {@code RECEPCION_YA_REGISTRADA} — nunca las dos.
 * <p><b>Invariantes:</b> el stock de destino queda en exactamente 6 (nunca
 * 12, nunca 0); la transferencia cierra en {@code RECEIVED_COMPLETE}
 * (nunca queda huérfana en {@code IN_TRANSIT} con la línea ya recibida).
 * <p><b>Evidencia en InventoryMovement:</b> exactamente un
 * {@code TRANSFERENCIA_ENTRADA} para esta línea.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class TransferReceiveConcurrencyTest {

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
        Branch origin = branchRepository.save(new Branch("SUC-CCRE-O", "Origen Recepción Concurrente", null));
        Branch destination = branchRepository.save(new Branch("SUC-CCRE-D", "Destino Recepción Concurrente", null));
        User operator = userRepository.save(new User(
                "Operador CC Recepción TR", "operator.cc.receive.tr@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, destination.getId()));
        User admin = userRepository.save(new User(
                "Admin CC Recepción TR", "admin.cc.receive.tr@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.ADMIN, null));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCRE", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CCRE-001", "Producto Recepción Concurrente", null, unit.getId()));
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
            Exception outcome = future.get(15, TimeUnit.SECONDS);
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
