package com.inventario.multisucursal.inventory;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * docs/CRITICAL_FLOWS.md, escenario 3.4 (rollback si falla un paso
 * intermedio): un ajuste de inventario escribe {@link Inventory} y
 * {@link InventoryMovement} dentro de una única transacción
 * ({@code @Transactional} en {@link InventoryMovementService#createAdjustment}).
 * Se fuerza una excepción al insertar el {@code InventoryMovement} (después
 * de que el `UPDATE` de {@code Inventory} ya se ejecutó dentro de esa misma
 * transacción, vía {@link InventoryMovementRepository} mockeado) y se
 * verifica que el aumento de stock también se revierte — no puede quedar un
 * `Inventory` actualizado sin su `InventoryMovement` correspondiente
 * (BR-001, BR-015).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class InventoryAdjustmentRollbackTest {

    @Autowired
    private InventoryMovementService movementService;

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
    void failureWhileInsertingMovementRollsBackTheInventoryUpdateToo() {
        Branch branch = branchRepository.save(new Branch("SUC-RB", "Sucursal Rollback", null));
        User operator = userRepository.save(new User(
                "Operador RB", "operator.rb@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-RB", "Unidad"));
        Product product = productRepository.save(new Product("SKU-RB-001", "Producto Rollback", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));

        AuthenticatedUser principal = new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branch.getId());
        authenticateAs(principal);

        // Primera llamada (siembra de stock inicial): pasa. Segunda llamada
        // (la que se prueba): el guardado del movimiento falla a mitad de la
        // transacción, después de que el UPDATE de Inventory ya se ejecutó.
        when(movementRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new RuntimeException("Fallo forzado a mitad de transacción"));

        try {
            movementService.createAdjustment(
                    new InventoryAdjustmentRequest(branch.getId(), product.getId(), null, MovementDirection.INGRESO, null, BigDecimal.TEN, "Stock inicial"),
                    operator.getId());

            assertThat(currentStock(product.getId(), branch.getId())).isEqualByComparingTo(BigDecimal.TEN);

            assertThatThrownBy(() -> movementService.createAdjustment(
                    new InventoryAdjustmentRequest(
                            branch.getId(), product.getId(), null, MovementDirection.INGRESO, null, new BigDecimal("5"), "Este ingreso no debe quedar aplicado"),
                    operator.getId()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Fallo forzado a mitad de transacción");

            assertThat(currentStock(product.getId(), branch.getId()))
                    .as("el incremento de stock del intento fallido debe revertirse junto con el movimiento no persistido")
                    .isEqualByComparingTo(BigDecimal.TEN);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private BigDecimal currentStock(Long productId, Long branchId) {
        return inventoryRepository.findByProductIdAndBranchId(productId, branchId).orElseThrow().getQuantityOnHand();
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
