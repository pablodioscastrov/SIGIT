package com.inventario.multisucursal.sales;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.inventory.InventoryAdjustmentRequest;
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
 * REQUISITO CRÍTICO: dos solicitudes de venta concurrentes no pueden vender
 * más stock del disponible (docs/CRITICAL_FLOWS.md, escenario 3.1). Llama
 * directamente a {@link SaleService} (no vía HTTP) para controlar el
 * arranque simultáneo con un {@link CountDownLatch} — mismo patrón que
 * {@code InventoryConcurrencyTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SaleConcurrencyTest {

    @Autowired
    private SaleService saleService;

    @Autowired
    private InventoryMovementService inventoryMovementService;

    @Autowired
    private InventoryRepository inventoryRepository;

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
    private PriceListRepository priceListRepository;

    @Autowired
    private PriceRepository priceRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void twoConcurrentSalesOnLastStockOnlyOneSucceeds() throws Exception {
        Branch branch = branchRepository.save(new Branch("SUC-CC", "Sucursal Concurrencia Ventas", null));
        User operator = userRepository.save(new User(
                "Operador CC", "operator.cc.sales@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CC-S", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CC-SALE-001", "Producto Concurrencia Venta", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        PriceList priceList = priceListRepository.save(new PriceList("Lista CC", null));
        priceRepository.save(new Price(priceList.getId(), product.getId(), new BigDecimal("10.00")));

        AuthenticatedUser principal = new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branch.getId());

        // Siembra de stock inicial vía el flujo real de ajuste manual (@Transactional),
        // no un UPDATE atómico crudo sobre el repositorio: InventoryRepository.applyQuantity
        // es un @Modifying y exige un contexto transaccional propio para ejecutarse.
        authenticateAs(principal);
        try {
            inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(branch.getId(), product.getId(), null, MovementDirection.INGRESO, null, BigDecimal.TEN, "Stock inicial de prueba"),
                    operator.getId());
        } finally {
            SecurityContextHolder.clearContext();
        }

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Exception>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                authenticateAs(principal);
                try {
                    ready.countDown();
                    start.await();
                    saleService.confirmSale(
                            new CreateSaleRequest(branch.getId(), priceList.getId(),
                                    List.of(new CreateSaleItemRequest(product.getId(), null, new BigDecimal("6"), null))),
                            operator.getId(),
                            UUID.randomUUID().toString());
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
        int businessFailures = 0;
        for (Future<Exception> future : futures) {
            Exception outcome = future.get(15, TimeUnit.SECONDS);
            if (outcome == null) {
                successes++;
            } else {
                assertThat(outcome).isInstanceOf(BusinessRuleViolationException.class);
                assertThat(((BusinessRuleViolationException) outcome).getCode()).isEqualTo("STOCK_INSUFICIENTE");
                businessFailures++;
            }
        }
        executor.shutdown();

        assertThat(successes).as("exactamente una venta debe confirmarse").isEqualTo(1);
        assertThat(businessFailures).as("la otra debe rechazarse por falta de stock").isEqualTo(1);

        BigDecimal finalStock = inventoryRepository.findByProductIdAndBranchId(product.getId(), branch.getId())
                .orElseThrow()
                .getQuantityOnHand();
        assertThat(finalStock).isEqualByComparingTo(new BigDecimal("4"));
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
