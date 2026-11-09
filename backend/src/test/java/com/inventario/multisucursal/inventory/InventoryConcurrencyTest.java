package com.inventario.multisucursal.inventory;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR-022 / docs/CRITICAL_FLOWS.md, sección 1.2 y escenario 3.1: dos retiros
 * concurrentes reales (hilos + transacciones independientes, no
 * secuenciales simuladas) sobre el mismo {@link Inventory}, cuando la suma
 * de ambos excede el stock disponible. Llama directamente a
 * {@link InventoryMovementService} (bean gestionado por Spring, no vía
 * HTTP) para poder controlar con precisión el arranque simultáneo de ambos
 * hilos con un {@link CountDownLatch}; cada hilo autentica su propio
 * {@code SecurityContext} porque {@code SecurityContextHolder} es
 * thread-local y no se propaga automáticamente a los hilos del executor.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class InventoryConcurrencyTest {

    @Autowired
    private InventoryMovementService movementService;

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
    private PasswordEncoder passwordEncoder;

    @Test
    void twoConcurrentWithdrawalsOnLastStockOnlyOneSucceeds() throws Exception {
        Branch branch = branchRepository.save(new Branch("SUC-CC", "Sucursal Concurrencia", null));
        User operator = userRepository.save(new User(
                "Operador CC", "operator.cc@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CC", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CC-001", "Producto Concurrencia", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));

        AuthenticatedUser principal = new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branch.getId());

        authenticateAs(principal);
        try {
            movementService.createAdjustment(
                    new InventoryAdjustmentRequest(branch.getId(), product.getId(), null, MovementDirection.INGRESO, null, BigDecimal.TEN, "Stock inicial"),
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
                    movementService.createAdjustment(
                            new InventoryAdjustmentRequest(
                                    branch.getId(), product.getId(), null, MovementDirection.RETIRO, null, new BigDecimal("6"), "Retiro concurrente"),
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

        assertThat(successes).as("exactamente un retiro debe confirmarse").isEqualTo(1);
        assertThat(businessFailures).as("el otro debe rechazarse por falta de stock").isEqualTo(1);

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
