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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * BR-010 / requisito explícito de esta fase: un fallo al evaluar o persistir
 * la alerta de stock <b>nunca</b> debe revertir la operación de inventario ya
 * confirmada que la disparó (venta, compra, transferencia, ajuste). Se
 * reemplaza {@link StockAlertRepository} por un doble que siempre falla —
 * {@link StockAlertService#evaluate} debe capturarlo y seguir sin propagar
 * nada— y se verifica con el camino más simple (un ajuste manual, que
 * ejercita exactamente el mismo método compartido que usan
 * ventas/compras/transferencias) que la operación de todos modos se
 * confirma y el stock queda efectivamente aplicado.
 *
 * <p>Clase de prueba separada de {@link StockAlertApiTest} a propósito: el
 * {@code @MockBean} reemplaza el repositorio real para <b>toda</b> la clase,
 * lo que haría inútiles las aserciones de esa otra clase sobre alertas
 * realmente persistidas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StockAlertNotificationFailureTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductUnitRepository productUnitRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryMovementService inventoryMovementService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private StockAlertRepository stockAlertRepository;

    private Branch branch;
    private User adminUser;
    private UnitOfMeasure unit;

    @BeforeEach
    void setUp() {
        productUnitRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
        unitOfMeasureRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branch = branchRepository.save(new Branch("SUC-A", "Sucursal A"));
        String hash = passwordEncoder.encode(SEED_PASSWORD);
        adminUser = userRepository.save(new User("Admin", "admin@test.local", hash, RoleCode.ADMIN, null));
        unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN", "Unidad"));

        // Cualquier lectura sobre el doble revienta — simula un fallo real de
        // infraestructura (no la condición de carrera ya contemplada, que ni
        // siquiera llega a la base de datos si el "exists" ya falla antes).
        when(stockAlertRepository.existsByInventoryIdAndStatus(any(), any())).thenThrow(new RuntimeException("Fallo simulado de infraestructura"));
        when(stockAlertRepository.resolveActive(any(), any())).thenThrow(new RuntimeException("Fallo simulado de infraestructura"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adjustmentThatCrossesTheThresholdStillCommitsWhenAlertEvaluationFails() {
        Product product = seedProduct("SKU-FAIL-1");
        authenticateAs();
        // Ingreso inicial: también pasa por evaluate() (con el mock ya
        // reventando) y aun así debe crear el Inventory y aplicar la cantidad.
        inventoryMovementService.createAdjustment(
                new InventoryAdjustmentRequest(branch.getId(), product.getId(), null, MovementDirection.INGRESO, null, new BigDecimal("20"), "Siembra"),
                adminUser.getId());

        // Retiro que cruzaría el umbral mínimo (evaluate() intentaría crear
        // una alerta y el mock lo revienta) — el ajuste debe confirmarse igual.
        InventoryMovementResponse response = inventoryMovementService.createAdjustment(
                new InventoryAdjustmentRequest(branch.getId(), product.getId(), null, MovementDirection.RETIRO, null, new BigDecimal("15"), "Retiro"),
                adminUser.getId());

        assertThat(response).isNotNull();
        Inventory inventory = inventoryRepository.findByProductIdAndBranchId(product.getId(), branch.getId()).orElseThrow();
        assertThat(inventory.getQuantityOnHand()).isEqualByComparingTo("5");
    }

    // ---- helpers ----

    private Product seedProduct(String sku) {
        Product product = productRepository.save(new Product(sku, "Producto " + sku, null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        return product;
    }

    private void authenticateAs() {
        AuthenticatedUser principal = new AuthenticatedUser(adminUser.getId(), "Test", "test@test.local", RoleCode.ADMIN, null);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }
}
