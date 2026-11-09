package com.inventario.multisucursal.common;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryAdjustmentRequest;
import com.inventario.multisucursal.inventory.InventoryMovement;
import com.inventario.multisucursal.inventory.InventoryMovementRepository;
import com.inventario.multisucursal.inventory.InventoryMovementService;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.inventory.MovementDirection;
import com.inventario.multisucursal.inventory.MovementReason;
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
import com.inventario.multisucursal.sales.CreateSaleItemRequest;
import com.inventario.multisucursal.sales.CreateSaleRequest;
import com.inventario.multisucursal.sales.Sale;
import com.inventario.multisucursal.sales.SaleRepository;
import com.inventario.multisucursal.sales.SaleService;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Mismo escenario y mismas aserciones que
 * {@link IdempotencyKeyRaceTest#sameIdempotencyKeyRaceOnSaleAppliesExactlyOnce},
 * pero contra PostgreSQL real vía Testcontainers — ver el javadoc de
 * {@link com.inventario.multisucursal.inventory.InventoryConcurrencyPostgresTest}
 * para la justificación general (docs/TEST_STRATEGY.md §4). Aquí importa en
 * particular porque el mecanismo bajo prueba es una violación de restricción
 * única detectada durante un {@code INSERT} concurrente — el tipo exacto de
 * excepción y el momento en que Postgres la reporta (durante la sentencia,
 * no al hacer commit) es justo lo que garantiza que el perdedor de la
 * carrera nunca llegue a aplicar el retiro de inventario; no hay garantía de
 * que H2 lo reporte con la misma semántica exacta. {@code
 * disabledWithoutDocker = true}: se omite con gracia sin Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyKeyRacePostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private SaleService saleService;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private InventoryMovementService inventoryMovementService;

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
    private PriceListRepository priceListRepository;

    @Autowired
    private PriceRepository priceRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void sameIdempotencyKeyRaceOnSaleAppliesExactlyOnce() throws Exception {
        Branch branch = branchRepository.save(new Branch("SUC-CCIDPG-S", "Sucursal Carrera Idempotencia Venta PG", null));
        User operator = userRepository.save(new User(
                "Operador CC Idem PG", "operator.cc.idem.sale.pg@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCIDPG-S", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CCIDPG-S-001", "Producto Carrera Idempotencia Venta PG", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        PriceList priceList = priceListRepository.save(new PriceList("Lista CC Idem PG", null));
        priceRepository.save(new Price(priceList.getId(), product.getId(), new BigDecimal("10.00")));

        AuthenticatedUser principal = new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branch.getId());

        authenticateAs(principal);
        try {
            inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(branch.getId(), product.getId(), null, MovementDirection.INGRESO, null, BigDecimal.TEN, "Stock inicial"),
                    operator.getId());
        } finally {
            SecurityContextHolder.clearContext();
        }

        String sharedIdempotencyKey = "e2e-timeout-retry-pg-" + java.util.UUID.randomUUID();

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
                                    List.of(new CreateSaleItemRequest(product.getId(), null, BigDecimal.ONE, null))),
                            operator.getId(),
                            sharedIdempotencyKey);
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
            Exception outcome = future.get(20, TimeUnit.SECONDS);
            if (outcome == null) {
                successes++;
            } else {
                assertThat(outcome)
                        .as("el perdedor de la carrera de INSERT debe fallar por violación de la restricción única, no por otra causa")
                        .isInstanceOf(DataIntegrityViolationException.class);
                conflicts++;
            }
        }
        executor.shutdown();

        assertThat(successes).as("exactamente una venta debe persistirse con esta clave").isEqualTo(1);
        assertThat(conflicts).as("la otra debe chocar contra la restricción única de client_reference_id").isEqualTo(1);

        List<Sale> salesWithThisKey = saleRepository.findAll().stream()
                .filter(s -> sharedIdempotencyKey.equals(s.getClientReferenceId()))
                .toList();
        assertThat(salesWithThisKey).as("nunca dos ventas con la misma clave de idempotencia").hasSize(1);

        BigDecimal finalStock = inventoryRepository.findByProductIdAndBranchId(product.getId(), branch.getId())
                .orElseThrow().getQuantityOnHand();
        assertThat(finalStock)
                .as("el retiro se aplicó exactamente una vez: 10 - 1 = 9, nunca 8 (el perdedor nunca llegó a descontar stock)")
                .isEqualByComparingTo(new BigDecimal("9"));

        List<InventoryMovement> saleMovements = movementRepository.findAll().stream()
                .filter(m -> m.getReason() == MovementReason.VENTA && m.getProductId().equals(product.getId()))
                .toList();
        assertThat(saleMovements).as("exactamente un movimiento de venta, nunca dos").hasSize(1);
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
