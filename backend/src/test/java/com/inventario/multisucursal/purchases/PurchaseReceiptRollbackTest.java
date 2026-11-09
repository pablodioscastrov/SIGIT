package com.inventario.multisucursal.purchases;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryMovementRepository;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.ProductUnit;
import com.inventario.multisucursal.products.ProductUnitRepository;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.suppliers.Supplier;
import com.inventario.multisucursal.suppliers.SupplierRepository;
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
 * docs/CRITICAL_FLOWS.md, escenario 3.4, aplicado al flujo B: la recepción
 * escribe {@code PurchaseOrderItem.quantityReceived}, {@code Inventory} y
 * {@code InventoryMovement} dentro de una única transacción
 * ({@code @Transactional} en {@link PurchaseReceiptService#receive}). Se
 * fuerza una excepción al insertar el {@code InventoryMovement} (después de
 * que los dos UPDATE anteriores ya se ejecutaron dentro de esa misma
 * transacción) y se verifica que ambos se revierten — no puede quedar una
 * recepción parcial internamente inconsistente (BR-016).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PurchaseReceiptRollbackTest {

    @Autowired
    private PurchaseReceiptService purchaseReceiptService;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

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
    private SupplierRepository supplierRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private InventoryMovementRepository movementRepository;

    @Test
    void failureWhileInsertingMovementRollsBackItemAndInventoryUpdates() {
        Branch branch = branchRepository.save(new Branch("SUC-RB", "Sucursal Rollback", null));
        User operator = userRepository.save(new User(
                "Operador RB", "operator.rb@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-RB", "Unidad"));
        Product product = productRepository.save(new Product("SKU-RB-001", "Producto Rollback", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        Supplier supplier = supplierRepository.save(new Supplier("Proveedor RB", "TAX-RB-001", null, null, null));
        PurchaseOrder order = purchaseOrderRepository.save(new PurchaseOrder("OC-RB-0001", supplier.getId(), branch.getId(), null, operator.getId()));
        PurchaseOrderItem item = purchaseOrderItemRepository.save(new PurchaseOrderItem(
                order.getId(), product.getId(), unit.getId(), BigDecimal.TEN, new BigDecimal("15.0000"), BigDecimal.ZERO, new BigDecimal("150.0000")));

        AuthenticatedUser principal = new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branch.getId());
        authenticateAs(principal);

        when(movementRepository.findByIdempotencyKey(any())).thenReturn(java.util.Optional.empty());
        when(movementRepository.save(any())).thenThrow(new RuntimeException("Fallo forzado a mitad de transacción"));

        try {
            PurchaseReceiptRequest request = new PurchaseReceiptRequest(
                    List.of(new ReceiptItemRequest(item.getId(), BigDecimal.TEN, new BigDecimal("15.0000"))));

            assertThatThrownBy(() -> purchaseReceiptService.receive(order.getId(), request, UUID.randomUUID().toString(), operator.getId()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Fallo forzado a mitad de transacción");

            PurchaseOrderItem reloadedItem = purchaseOrderItemRepository.findById(item.getId()).orElseThrow();
            assertThat(reloadedItem.getQuantityReceived())
                    .as("la cantidad recibida no debe quedar aplicada si el movimiento no se pudo insertar")
                    .isEqualByComparingTo(BigDecimal.ZERO);

            assertThat(inventoryRepository.findByProductIdAndBranchId(product.getId(), branch.getId()))
                    .as("el inventario (creado dentro de la misma transacción fallida) tampoco debe quedar persistido")
                    .isEmpty();

            PurchaseOrder reloadedOrder = purchaseOrderRepository.findById(order.getId()).orElseThrow();
            assertThat(reloadedOrder.getStatus())
                    .as("el estado de la orden no debe avanzar si la recepción no se completó")
                    .isEqualTo(PurchaseOrderStatus.CREATED);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
