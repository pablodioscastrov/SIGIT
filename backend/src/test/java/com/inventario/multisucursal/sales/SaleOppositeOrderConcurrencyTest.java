package com.inventario.multisucursal.sales;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 8 del encargo de confiabilidad/concurrencia: "deadlock/lock
 * timeout razonable si la estrategia lo hace posible".
 *
 * <p><b>Por qué esta prueba, y no una de deadlock clásico:</b> todo el
 * bloqueo de este proyecto es <b>optimista</b> — un {@code UPDATE ... WHERE
 * id = ? AND version = ?} con reintento manual acotado a 3 intentos
 * ({@code InventoryRepository.applyQuantity}/{@code applyReceipt}), nunca
 * {@code SELECT ... FOR UPDATE} ni ningún otro bloqueo pesimista mantenido
 * a través de varias sentencias. Un deadlock de base de datos en el sentido
 * clásico —dos transacciones cada una reteniendo el lock que la otra
 * espera— requiere locks retenidos; aquí no los hay, así que un deadlock
 * real no es posible por diseño. Lo que <b>sí</b> es posible, y es lo que
 * esta prueba ejercita, es el riesgo relacionado que señala
 * `docs/TEST_STRATEGY.md`: {@code SaleService.confirmSale} recorre las
 * líneas de la venta <b>en el orden que llegaron en el payload</b>, sin
 * ordenarlas por producto — dos ventas concurrentes sobre los mismos dos
 * productos en <b>orden opuesto</b> es la situación que, bajo bloqueo
 * pesimista, produciría un deadlock real; bajo bloqueo optimista con
 * reintento acotado, el riesgo se traduce en posible inanición del
 * reintento (que una de las dos termine en {@code CONFLICTO_CONCURRENCIA}
 * aunque el stock alcance de sobra para ambas).
 *
 * <p><b>Estado inicial:</b> dos productos, A y B, cada uno con 20 unidades
 * en la misma sucursal.
 * <p><b>Hilos:</b> 2. Hilo 1 vende [A: 5, B: 5] (ese orden). Hilo 2 vende
 * [B: 5, A: 5] (orden inverso) — mismos dos productos, misma sucursal,
 * demanda conjunta de 10 de cada uno contra 20 disponibles: ninguna de las
 * dos debería fallar por falta de stock.
 * <p><b>Barrera:</b> {@link CountDownLatch} doble; cada {@code Future.get}
 * lleva un tope de tiempo explícito — si la estrategia permitiera un
 * interbloqueo real, esta prueba fallaría por timeout en vez de colgarse
 * sin límite, que es justamente la propiedad que pide el escenario 8
 * ("lock timeout razonable").
 * <p><b>Resultado permitido:</b> <b>ambas</b> ventas se confirman — no hay
 * ningún motivo de negocio para rechazar ninguna, así que un
 * {@code CONFLICTO_CONCURRENCIA} aquí sería inanición del reintento, no una
 * decisión de negocio correcta, y haría fallar la prueba en vez de
 * disfrazarse de "resultado válido".
 * <p><b>Invariantes:</b> stock final de A y de B = 20 − 5 − 5 = 10 cada
 * uno; nunca negativo, nunca con una venta aplicada a medias.
 * <p><b>Evidencia en InventoryMovement:</b> cuatro movimientos
 * {@code VENTA} (dos por venta), cantidad 5 cada uno.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SaleOppositeOrderConcurrencyTest {

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
    void twoConcurrentMultiItemSalesInOppositeProductOrderBothSucceedWithoutHanging() throws Exception {
        Branch branch = branchRepository.save(new Branch("SUC-CCOO", "Sucursal Orden Opuesto", null));
        User operator = userRepository.save(new User(
                "Operador CC Orden Opuesto", "operator.cc.oo@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCOO", "Unidad"));
        Product productA = productRepository.save(new Product("SKU-CCOO-A", "Producto A Orden Opuesto", null, unit.getId()));
        Product productB = productRepository.save(new Product("SKU-CCOO-B", "Producto B Orden Opuesto", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(productA.getId(), unit.getId(), BigDecimal.ONE, true));
        productUnitRepository.save(new ProductUnit(productB.getId(), unit.getId(), BigDecimal.ONE, true));
        PriceList priceList = priceListRepository.save(new PriceList("Lista CC Orden Opuesto", null));
        priceRepository.save(new Price(priceList.getId(), productA.getId(), new BigDecimal("10.00")));
        priceRepository.save(new Price(priceList.getId(), productB.getId(), new BigDecimal("10.00")));

        AuthenticatedUser principal = new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branch.getId());

        authenticateAs(principal);
        try {
            inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(branch.getId(), productA.getId(), null, MovementDirection.INGRESO, null, new BigDecimal("20"), "Stock inicial A"),
                    operator.getId());
            inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(branch.getId(), productB.getId(), null, MovementDirection.INGRESO, null, new BigDecimal("20"), "Stock inicial B"),
                    operator.getId());
        } finally {
            SecurityContextHolder.clearContext();
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Exception> saleAThenB = executor.submit(() -> {
            authenticateAs(principal);
            try {
                ready.countDown();
                start.await();
                saleService.confirmSale(
                        new CreateSaleRequest(branch.getId(), priceList.getId(), List.of(
                                new CreateSaleItemRequest(productA.getId(), null, new BigDecimal("5"), null),
                                new CreateSaleItemRequest(productB.getId(), null, new BigDecimal("5"), null))),
                        operator.getId(), UUID.randomUUID().toString());
                return null;
            } catch (Exception e) {
                return e;
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        Future<Exception> saleBThenA = executor.submit(() -> {
            authenticateAs(principal);
            try {
                ready.countDown();
                start.await();
                saleService.confirmSale(
                        new CreateSaleRequest(branch.getId(), priceList.getId(), List.of(
                                new CreateSaleItemRequest(productB.getId(), null, new BigDecimal("5"), null),
                                new CreateSaleItemRequest(productA.getId(), null, new BigDecimal("5"), null))),
                        operator.getId(), UUID.randomUUID().toString());
                return null;
            } catch (Exception e) {
                return e;
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        // Tope de tiempo explícito: un interbloqueo real terminaría en
        // TimeoutException aquí, no en un cuelgue indefinido — es justo la
        // propiedad de "lock timeout razonable" que pide el escenario 8.
        Exception outcomeAB = saleAThenB.get(10, TimeUnit.SECONDS);
        Exception outcomeBA = saleBThenA.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(outcomeAB)
                .as("no hay motivo de negocio para rechazar la venta [A,B]: 5+5=10 de cada producto contra 20 disponibles")
                .isNull();
        assertThat(outcomeBA)
                .as("no hay motivo de negocio para rechazar la venta [B,A]: el orden de las líneas no debería causar un rechazo")
                .isNull();

        BigDecimal finalStockA = inventoryRepository.findByProductIdAndBranchId(productA.getId(), branch.getId())
                .orElseThrow().getQuantityOnHand();
        BigDecimal finalStockB = inventoryRepository.findByProductIdAndBranchId(productB.getId(), branch.getId())
                .orElseThrow().getQuantityOnHand();
        assertThat(finalStockA).as("A: 20 - 5 - 5 = 10").isEqualByComparingTo(new BigDecimal("10"));
        assertThat(finalStockB).as("B: 20 - 5 - 5 = 10").isEqualByComparingTo(new BigDecimal("10"));
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
