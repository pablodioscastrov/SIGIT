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
 * Rollback parcial (docs/CRITICAL_FLOWS.md, escenario 3.4) aplicado al
 * despacho, que es el paso con más escrituras heterogéneas en una sola
 * transacción: estado de la transferencia + cantidad despachada de la línea +
 * descuento de {@code Inventory} + {@code InventoryMovement}.
 *
 * <p>Se fuerza el fallo al insertar el movimiento — el último paso — para
 * verificar que <b>los tres anteriores</b> se revierten. Es el caso más
 * peligroso del módulo: un despacho a medias dejaría stock descontado en el
 * origen sin que la transferencia conste como despachada, es decir,
 * mercancía desaparecida del sistema.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class TransferRollbackTest {

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
    void failureWhileInsertingMovementRollsBackDispatchEntirely() {
        Branch origin = branchRepository.save(new Branch("SUC-RB-O", "Origen Rollback", null));
        Branch destination = branchRepository.save(new Branch("SUC-RB-D", "Destino Rollback", null));
        User admin = userRepository.save(new User(
                "Admin RB", "admin.rb.transfer@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.ADMIN, null));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-RB-T", "Unidad"));
        Product product = productRepository.save(new Product("SKU-RB-TR-001", "Producto Rollback Transfer", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));

        AuthenticatedUser principal = new AuthenticatedUser(admin.getId(), admin.getName(), admin.getEmail(), RoleCode.ADMIN, null);
        authenticateAs(principal);

        // La primera llamada (siembra de stock) pasa; la segunda (el despacho) falla.
        when(movementRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new RuntimeException("Fallo forzado a mitad de transacción"));

        try {
            inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(origin.getId(), product.getId(), null, MovementDirection.INGRESO, null, BigDecimal.TEN, "Stock inicial"),
                    admin.getId());

            var transfer = transferService.request(
                    new CreateTransferRequest(origin.getId(), destination.getId(), false,
                            List.of(new CreateTransferItemRequest(product.getId(), new BigDecimal("4")))),
                    admin.getId(), UUID.randomUUID().toString());
            Long transferId = Long.valueOf(transfer.id());
            Long itemId = Long.valueOf(transfer.items().get(0).id());
            transferService.approve(transferId, new ApproveTransferRequest(
                    List.of(new ApproveTransferItemRequest(itemId, new BigDecimal("4")))), admin.getId());

            assertThatThrownBy(() -> transferService.dispatch(transferId, new DispatchTransferRequest(
                    "Transportes XYZ", null, List.of(new DispatchTransferItemRequest(itemId, new BigDecimal("4")))),
                    admin.getId()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Fallo forzado a mitad de transacción");

            assertThat(inventoryRepository.findByProductIdAndBranchId(product.getId(), origin.getId()).orElseThrow().getQuantityOnHand())
                    .as("el stock descontado por el despacho fallido debe volver a su valor previo")
                    .isEqualByComparingTo(BigDecimal.TEN);
            assertThat(transferRepository.findById(transferId).orElseThrow().getStatus())
                    .as("la transferencia no puede quedar IN_TRANSIT si el despacho no se completó")
                    .isEqualTo(TransferStatus.APPROVED);
            assertThat(transferItemRepository.findById(itemId).orElseThrow().getQuantityShipped())
                    .as("la línea no puede quedar marcada como despachada")
                    .isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
