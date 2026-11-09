package com.inventario.multisucursal.transfers;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryAdjustmentRequest;
import com.inventario.multisucursal.inventory.InventoryMovementRepository;
import com.inventario.multisucursal.inventory.InventoryMovementService;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.inventory.MovementDirection;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Escenario 7 del encargo de confiabilidad/concurrencia, aplicado al paso de
 * <b>recepción</b> de una transferencia — {@link TransferRollbackTest} ya
 * cubre el mismo principio para el <b>despacho</b>, pero no existía ningún
 * equivalente para la recepción (brecha señalada en
 * `docs/TEST_STRATEGY.md` §Part 1 al auditar la cobertura existente).
 *
 * <p>Se fuerza el fallo al insertar el {@code InventoryMovement} de la
 * recepción — el último paso de {@code TransferService.receive(...)} —
 * después de que ya corrieron, en la misma transacción: el guard atómico
 * {@code markReceived} (que marcó la línea como recibida) y el incremento
 * de {@code Inventory} en destino (que, en este caso, además creó la fila
 * de {@code Inventory} por primera vez, porque la sucursal destino nunca
 * había tenido el producto). Verifica que {@code @Transactional} revierte
 * los tres: sin esto, una recepción a medias dejaría una línea marcada como
 * "recibida" sin que el stock de destino realmente la refleje — mercancía
 * que llegó físicamente pero que el sistema no sabría que tiene.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class TransferReceiveRollbackTest {

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
    private BranchRepository branchRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @Autowired
    private ProductUnitRepository productUnitRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private InventoryMovementRepository movementRepository;

    @Test
    void failureWhileInsertingMovementRollsBackReceiveEntirely() {
        Branch origin = branchRepository.save(new Branch("SUC-RBR-O", "Origen Rollback Recepción", null));
        Branch destination = branchRepository.save(new Branch("SUC-RBR-D", "Destino Rollback Recepción", null));
        User admin = userRepository.save(new User(
                "Admin RB Recepción", "admin.rb.receive@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.ADMIN, null));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-RBR", "Unidad"));
        Product product = productRepository.save(new Product("SKU-RBR-001", "Producto Rollback Recepción", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));

        AuthenticatedUser principal = new AuthenticatedUser(admin.getId(), admin.getName(), admin.getEmail(), RoleCode.ADMIN, null);
        authenticateAs(principal);

        // 1ª llamada (siembra de stock) y 2ª (movimiento del despacho) pasan;
        // la 3ª (movimiento de la recepción, lo que esta prueba ataca) falla.
        when(movementRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new RuntimeException("Fallo forzado a mitad de transacción"));

        try {
            inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(origin.getId(), product.getId(), null, MovementDirection.INGRESO, null, BigDecimal.TEN, "Stock inicial"),
                    admin.getId());

            var transfer = transferService.request(
                    new CreateTransferRequest(origin.getId(), destination.getId(), false,
                            List.of(new CreateTransferItemRequest(product.getId(), new BigDecimal("6")))),
                    admin.getId(), UUID.randomUUID().toString());
            Long transferId = Long.valueOf(transfer.id());
            Long itemId = Long.valueOf(transfer.items().get(0).id());
            transferService.approve(transferId, new ApproveTransferRequest(
                    List.of(new ApproveTransferItemRequest(itemId, new BigDecimal("6")))), admin.getId());
            transferService.dispatch(transferId, new DispatchTransferRequest(
                    "Transportes XYZ", null, List.of(new DispatchTransferItemRequest(itemId, new BigDecimal("6")))), admin.getId());

            // Hasta aquí, todo real y verificado: el despacho sí se aplicó.
            assertThat(inventoryRepository.findByProductIdAndBranchId(product.getId(), origin.getId()).orElseThrow().getQuantityOnHand())
                    .isEqualByComparingTo(new BigDecimal("4"));

            assertThatThrownBy(() -> transferService.receive(transferId,
                    new ReceiveTransferRequest(List.of(new ReceiveTransferItemRequest(itemId, new BigDecimal("6")))), admin.getId()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Fallo forzado a mitad de transacción");

            assertThat(inventoryRepository.findByProductIdAndBranchId(product.getId(), destination.getId()))
                    .as("la fila de Inventory de destino, creada por primera vez dentro de esta misma transacción fallida, no debe quedar")
                    .isEmpty();
            assertThat(transferItemRepository.findById(itemId).orElseThrow().getQuantityReceived())
                    .as("la línea no puede quedar marcada como recibida")
                    .isNull();
            assertThat(transferRepository.findById(transferId).orElseThrow().getStatus())
                    .as("la transferencia no puede avanzar de estado si la recepción no se completó")
                    .isEqualTo(TransferStatus.IN_TRANSIT);
            assertThat(inventoryRepository.findByProductIdAndBranchId(product.getId(), origin.getId()).orElseThrow().getQuantityOnHand())
                    .as("el stock de origen, ya comprometido por el despacho previo (real, no revertido), no debe verse afectado por esta recepción fallida")
                    .isEqualByComparingTo(new BigDecimal("4"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
