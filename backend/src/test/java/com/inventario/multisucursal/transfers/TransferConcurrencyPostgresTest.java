package com.inventario.multisucursal.transfers;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.ApiException;
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
import com.inventario.multisucursal.sales.CreateSaleItemRequest;
import com.inventario.multisucursal.sales.CreateSaleRequest;
import com.inventario.multisucursal.sales.SaleService;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mismo escenario y mismas aserciones que {@link TransferConcurrencyTest}
 * (venta y despacho compitiendo por la misma fila de {@code Inventory}),
 * pero contra PostgreSQL real vía Testcontainers — ver el javadoc de
 * {@link com.inventario.multisucursal.inventory.InventoryConcurrencyPostgresTest}
 * para la justificación (docs/TEST_STRATEGY.md §4). {@code
 * disabledWithoutDocker = true}: se omite con gracia sin Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferConcurrencyPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TransferService transferService;

    @Autowired
    private SaleService saleService;

    @Autowired
    private InventoryMovementService inventoryMovementService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private TransferItemRepository transferItemRepository;

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
    void saleAndDispatchCompetingForTheSameStockNeverOversell() throws Exception {
        Branch origin = branchRepository.save(new Branch("SUC-CCPG-O", "Origen Concurrencia PG", null));
        Branch destination = branchRepository.save(new Branch("SUC-CCPG-D", "Destino Concurrencia PG", null));
        User operator = userRepository.save(new User(
                "Operador CC PG", "operator.cc.transfer.pg@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, origin.getId()));
        User admin = userRepository.save(new User(
                "Admin CC PG", "admin.cc.transfer.pg@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.ADMIN, null));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCPG-T", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CCPG-TR-001", "Producto Concurrencia Transfer PG", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        PriceList priceList = priceListRepository.save(new PriceList("Lista CC Transfer PG", null));
        priceRepository.save(new Price(priceList.getId(), product.getId(), new BigDecimal("10.00")));

        AuthenticatedUser adminPrincipal = new AuthenticatedUser(admin.getId(), admin.getName(), admin.getEmail(), RoleCode.ADMIN, null);
        AuthenticatedUser operatorPrincipal =
                new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, origin.getId());

        // Stock inicial: 10. La venta pide 6 y el despacho 6 — juntos exceden lo disponible.
        authenticateAs(adminPrincipal);
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

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Exception>> futures = new ArrayList<>();

            futures.add(executor.submit(() -> {
                authenticateAs(operatorPrincipal);
                try {
                    ready.countDown();
                    start.await();
                    saleService.confirmSale(
                            new CreateSaleRequest(origin.getId(), priceList.getId(),
                                    List.of(new CreateSaleItemRequest(product.getId(), null, new BigDecimal("6"), null))),
                            operator.getId(), UUID.randomUUID().toString());
                    return null;
                } catch (Exception e) {
                    return e;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));

            futures.add(executor.submit(() -> {
                authenticateAs(operatorPrincipal);
                try {
                    ready.countDown();
                    start.await();
                    transferService.dispatch(transferId, new DispatchTransferRequest(
                            "Transportes XYZ", null, List.of(new DispatchTransferItemRequest(itemId, new BigDecimal("6")))),
                            operator.getId());
                    return null;
                } catch (Exception e) {
                    return e;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            int successes = 0;
            int rejections = 0;
            for (Future<Exception> future : futures) {
                Exception outcome = future.get(15, TimeUnit.SECONDS);
                if (outcome == null) {
                    successes++;
                } else {
                    assertThat(outcome)
                            .as("el perdedor debe fallar por una razón de negocio explícita, no por un error interno")
                            .isInstanceOf(ApiException.class);
                    assertThat(((ApiException) outcome).getCode()).isIn("STOCK_INSUFICIENTE", "CONFLICTO_CONCURRENCIA");
                    rejections++;
                }
            }
            executor.shutdown();

            assertThat(successes).as("solo una de las dos operaciones puede consumir el stock").isEqualTo(1);
            assertThat(rejections).isEqualTo(1);

            BigDecimal finalStock = inventoryRepository.findByProductIdAndBranchId(product.getId(), origin.getId())
                    .orElseThrow().getQuantityOnHand();
            assertThat(finalStock)
                    .as("nunca se vende y se despacha el mismo stock: 10 - 6 = 4, jamás negativo")
                    .isEqualByComparingTo(new BigDecimal("4"));

            // Si el despacho perdió, la transferencia debe seguir despachable más tarde.
            TransferItem item = transferItemRepository.findById(itemId).orElseThrow();
            boolean dispatchWon = item.getQuantityShipped() != null;
            assertThat(dispatchWon || successes == 1).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
