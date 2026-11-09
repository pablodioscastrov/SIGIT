package com.inventario.multisucursal.sales;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryAdjustmentRequest;
import com.inventario.multisucursal.inventory.InventoryMovementRepository;
import com.inventario.multisucursal.inventory.InventoryMovementService;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.inventory.MovementDirection;
import com.inventario.multisucursal.products.Price;
import com.inventario.multisucursal.products.PriceList;
import com.inventario.multisucursal.products.PriceListRepository;
import com.inventario.multisucursal.products.PriceRepository;
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
 * docs/CRITICAL_FLOWS.md, escenario 3.4, aplicado al flujo A: confirmar una
 * venta escribe {@code Inventory}, {@code SaleItem}, {@code InventoryMovement}
 * y, al final, los totales de {@code Sale} dentro de una única transacción.
 * Se fuerza una excepción al insertar el {@code InventoryMovement} (después
 * de que el decremento de stock ya se ejecutó) y se verifica que todo se
 * revierte — no puede quedar una venta con algunas líneas aplicadas y el
 * stock ya descontado sin su movimiento correspondiente (BR-001, BR-015).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SaleRollbackTest {

    @Autowired
    private SaleService saleService;

    @Autowired
    private InventoryMovementService inventoryMovementService;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private SaleItemRepository saleItemRepository;

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
    private PriceListRepository priceListRepository;

    @Autowired
    private PriceRepository priceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private InventoryMovementRepository movementRepository;

    @Test
    void failureWhileInsertingMovementRollsBackStockAndTheSaleItself() {
        Branch branch = branchRepository.save(new Branch("SUC-RB-V", "Sucursal Rollback Venta", null));
        User operator = userRepository.save(new User(
                "Operador RB", "operator.rb.sales@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-RB-V", "Unidad"));
        Product product = productRepository.save(new Product("SKU-RB-SALE-001", "Producto Rollback Venta", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        PriceList priceList = priceListRepository.save(new PriceList("Lista RB", null));
        priceRepository.save(new Price(priceList.getId(), product.getId(), new BigDecimal("10.00")));

        AuthenticatedUser principal = new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branch.getId());
        authenticateAs(principal);

        // Primera llamada a movementRepository.save (siembra de stock inicial vía el
        // flujo real de ajuste manual): pasa. Segunda llamada (la venta bajo prueba):
        // falla a mitad de transacción, después de que el retiro de Inventory ya se
        // ejecutó — mismo patrón que InventoryAdjustmentRollbackTest/PurchaseReceiptRollbackTest.
        when(movementRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new RuntimeException("Fallo forzado a mitad de transacción"));

        inventoryMovementService.createAdjustment(
                new InventoryAdjustmentRequest(branch.getId(), product.getId(), null, MovementDirection.INGRESO, null, BigDecimal.TEN, "Stock inicial de prueba"),
                operator.getId());

        try {
            CreateSaleRequest request = new CreateSaleRequest(
                    branch.getId(), priceList.getId(), List.of(new CreateSaleItemRequest(product.getId(), null, new BigDecimal("4"), null)));
            String idempotencyKey = UUID.randomUUID().toString();

            assertThatThrownBy(() -> saleService.confirmSale(request, operator.getId(), idempotencyKey))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Fallo forzado a mitad de transacción");

            BigDecimal stockAfterFailure = inventoryRepository.findByProductIdAndBranchId(product.getId(), branch.getId())
                    .orElseThrow()
                    .getQuantityOnHand();
            assertThat(stockAfterFailure)
                    .as("el decremento de stock del intento fallido debe revertirse junto con el movimiento no persistido")
                    .isEqualByComparingTo(BigDecimal.TEN);

            // No se compara la tabla completa (isEmpty()): @SpringBootTest reutiliza el
            // mismo contexto/base H2 entre clases de test con igual configuración, así
            // que puede haber ventas de otras clases ya persistidas. Se verifica en
            // cambio que ESTA venta en particular (por su clave de idempotencia) y su
            // línea (por su producto, único de este test) no quedaron persistidas.
            assertThat(saleRepository.findByClientReferenceId(idempotencyKey))
                    .as("la venta no debe quedar persistida si la transacción falló")
                    .isEmpty();
            assertThat(saleItemRepository.findAll().stream().noneMatch(item -> item.getProductId().equals(product.getId())))
                    .as("ninguna línea de venta de este producto debe quedar persistida")
                    .isTrue();
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
