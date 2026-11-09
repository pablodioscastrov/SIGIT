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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mismo escenario y mismas aserciones que {@link InventoryConcurrencyTest}
 * (BR-022 / docs/CRITICAL_FLOWS.md, escenario 3.1), pero contra PostgreSQL
 * real vía Testcontainers en vez de H2 — docs/TEST_STRATEGY.md §4: H2 en
 * modo compatibilidad no garantiza el mismo comportamiento de bloqueo de
 * fila ni de aborto de transacción que Postgres, y esta es exactamente la
 * prueba que ejercita ese mecanismo (bloqueo optimista con reintento sobre
 * {@code Inventory.version}). {@code disabledWithoutDocker = true}: se omite
 * con gracia si no hay Docker disponible, igual que
 * {@link com.inventario.multisucursal.FlywayMigrationIntegrationTest} — el
 * resto de la suite no depende de Docker.
 *
 * <p>Se deja correr Flyway de verdad (sin perfil "test", sin
 * {@code ddl-auto} propio) para validar el mecanismo contra el esquema real
 * de producción, no uno inferido por Hibernate.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InventoryConcurrencyPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
        Branch branch = branchRepository.save(new Branch("SUC-CCPG", "Sucursal Concurrencia PG", null));
        User operator = userRepository.save(new User(
                "Operador CC PG", "operator.cc.pg@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCPG", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CCPG-001", "Producto Concurrencia PG", null, unit.getId()));
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
