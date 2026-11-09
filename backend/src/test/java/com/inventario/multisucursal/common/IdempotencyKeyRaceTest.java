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
import com.inventario.multisucursal.transfers.CreateTransferItemRequest;
import com.inventario.multisucursal.transfers.CreateTransferRequest;
import com.inventario.multisucursal.transfers.Transfer;
import com.inventario.multisucursal.transfers.TransferRepository;
import com.inventario.multisucursal.transfers.TransferService;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Escenario 6 del encargo de confiabilidad/concurrencia: "reintento HTTP
 * después de timeout aparente" — un cliente que no recibió respuesta a
 * tiempo (por su reloj, no por el servidor) reintenta con el <b>mismo</b>
 * {@code Idempotency-Key} mientras el primer envío puede seguir en curso.
 * Las pruebas de idempotencia ya existentes ({@code SaleApiTest.retryingSameIdempotencyKeyDoesNotCreateASecondSale},
 * {@code PurchaseReceiptApiTest}, {@code TransferApiTest}) son todas
 * <b>secuenciales</b>: el segundo envío arranca después de que el primero ya
 * comprometió su commit, así que siempre entra por el camino barato
 * ("ya existe, se devuelve") — nunca ejercitan la ventana real de carrera
 * entre la comprobación ({@code findByClientReferenceId}) y el
 * {@code INSERT}, señalada explícitamente en el javadoc de
 * {@code GlobalExceptionHandler.handleDataIntegrityViolation} como "la
 * carrera residual". Esta prueba sí dispara esa ventana con hilos reales.
 *
 * <p><b>Mecanismo bajo prueba:</b> {@code SaleService.confirmSale}/{@code
 * TransferService.request} hacen primero un {@code findByClientReferenceId}
 * (TOCTOU: ninguno de los dos hilos ve todavía la fila del otro) y luego un
 * {@code INSERT} con {@code IDENTITY} —que Hibernate ejecuta de inmediato,
 * no en el flush final— sobre una columna {@code UNIQUE}. El respaldo real
 * de la idempotencia bajo concurrencia genuina es esa restricción de base de
 * datos, no la comprobación en memoria.
 *
 * <p><b>Estado inicial (venta):</b> stock = 10 en la sucursal.
 * <p><b>Hilos:</b> 2, ambos confirman la <b>misma</b> venta (mismo
 * {@code Idempotency-Key}, mismo producto, cantidad 1 — deliberadamente muy
 * por debajo del stock disponible, para aislar el invariante de idempotencia
 * del de stock insuficiente, que ya prueba {@code SaleConcurrencyTest}).
 * <p><b>Barrera:</b> {@link CountDownLatch} doble.
 * <p><b>Resultado permitido:</b> exactamente un hilo persiste la venta (el
 * que gana la carrera de {@code INSERT}); el otro recibe
 * {@link DataIntegrityViolationException} (que la API traduce a 409
 * {@code CONFLICTO_DATOS} — ver {@code GlobalExceptionHandler}) — nunca dos
 * ventas, y el perdedor nunca aplicó el retiro de inventario: al insertarse
 * la cabecera de {@code Sale} antes que cualquier línea, la excepción ocurre
 * antes de llegar al bucle que descuenta stock, y el rollback de la
 * transacción del perdedor deja cualquier trabajo previo sin efecto.
 * <p><b>Invariantes:</b> exactamente una fila con ese
 * {@code client_reference_id}; el stock desciende exactamente una vez (10 −
 * 1 = 9, nunca 8).
 * <p><b>Evidencia en InventoryMovement:</b> exactamente un movimiento
 * {@code VENTA} para esa venta.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IdempotencyKeyRaceTest {

    @Autowired
    private SaleService saleService;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferRepository transferRepository;

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
        Branch branch = branchRepository.save(new Branch("SUC-CCID-S", "Sucursal Carrera Idempotencia Venta", null));
        User operator = userRepository.save(new User(
                "Operador CC Idem", "operator.cc.idem.sale@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCID-S", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CCID-S-001", "Producto Carrera Idempotencia Venta", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        PriceList priceList = priceListRepository.save(new PriceList("Lista CC Idem", null));
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

        String sharedIdempotencyKey = "e2e-timeout-retry-" + java.util.UUID.randomUUID();

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
            Exception outcome = future.get(15, TimeUnit.SECONDS);
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

        // Filtrado por producto, no solo por motivo: el contexto de Spring se
        // reutiliza entre clases de prueba con la misma configuración (mismo
        // @SpringBootTest/@ActiveProfiles/@TestPropertySource), así que el H2
        // en memoria puede acumular movimientos VENTA de otras clases dentro
        // de la misma ejecución de `mvn test` — cada prueba usa un producto
        // con SKU único, así que acotar por productId aísla exactamente lo
        // que esta prueba generó, sin depender de que sea la única en la base.
        List<InventoryMovement> saleMovements = movementRepository.findAll().stream()
                .filter(m -> m.getReason() == MovementReason.VENTA && m.getProductId().equals(product.getId()))
                .toList();
        assertThat(saleMovements).as("exactamente un movimiento de venta, nunca dos").hasSize(1);
    }

    @Test
    void sameIdempotencyKeyRaceOnTransferRequestAppliesExactlyOnce() throws Exception {
        Branch origin = branchRepository.save(new Branch("SUC-CCID-T-O", "Origen Carrera Idempotencia Transfer", null));
        Branch destination = branchRepository.save(new Branch("SUC-CCID-T-D", "Destino Carrera Idempotencia Transfer", null));
        User admin = userRepository.save(new User(
                "Admin CC Idem Transfer", "admin.cc.idem.transfer@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.ADMIN, null));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCID-T", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CCID-T-001", "Producto Carrera Idempotencia Transfer", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));

        AuthenticatedUser adminPrincipal = new AuthenticatedUser(admin.getId(), admin.getName(), admin.getEmail(), RoleCode.ADMIN, null);

        String sharedIdempotencyKey = "e2e-timeout-retry-transfer-" + java.util.UUID.randomUUID();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Exception>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                authenticateAs(adminPrincipal);
                try {
                    ready.countDown();
                    start.await();
                    transferService.request(
                            new CreateTransferRequest(origin.getId(), destination.getId(), false,
                                    List.of(new CreateTransferItemRequest(product.getId(), BigDecimal.ONE))),
                            admin.getId(), sharedIdempotencyKey);
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
            Exception outcome = future.get(15, TimeUnit.SECONDS);
            if (outcome == null) {
                successes++;
            } else {
                assertThat(outcome).isInstanceOf(DataIntegrityViolationException.class);
                conflicts++;
            }
        }
        executor.shutdown();

        assertThat(successes).as("exactamente una transferencia debe persistirse con esta clave").isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        List<Transfer> transfersWithThisKey = transferRepository.findAll().stream()
                .filter(t -> sharedIdempotencyKey.equals(t.getClientReferenceId()))
                .toList();
        assertThat(transfersWithThisKey).as("nunca dos transferencias con la misma clave de idempotencia").hasSize(1);
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
