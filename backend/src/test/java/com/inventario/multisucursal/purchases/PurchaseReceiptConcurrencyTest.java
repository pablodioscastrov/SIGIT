package com.inventario.multisucursal.purchases;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.inventory.InventoryMovement;
import com.inventario.multisucursal.inventory.InventoryMovementRepository;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.inventory.MovementReason;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.ProductUnit;
import com.inventario.multisucursal.products.ProductUnitRepository;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.suppliers.Supplier;
import com.inventario.multisucursal.suppliers.SupplierRepository;
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
 * Escenario 3 del encargo de confiabilidad/concurrencia: dos confirmaciones
 * de recepción reales (hilos independientes, no HTTP secuencial) sobre la
 * <b>misma línea</b> de la <b>misma orden de compra</b>, cuando la suma de
 * ambas excede lo pendiente. {@link PurchaseOrderApiTest}/
 * {@link PurchaseReceiptApiTest} ya prueban idempotencia (mismo
 * {@code Idempotency-Key}, reintento secuencial) y "orden ya recibida"
 * (también secuencial) — ninguna de las dos ejercita una carrera real como
 * esta.
 *
 * <p><b>Estado inicial:</b> orden CREATED con una línea, cantidad ordenada
 * 10, nada recibido (pendiente = 10).
 * <p><b>Hilos:</b> 2, cada uno confirma una recepción de 6 unidades de la
 * misma línea, con su propia {@code Idempotency-Key} distinta (son dos
 * confirmaciones legítimas y separadas, no un reintento del mismo envío —
 * el reintento con la misma clave ya está cubierto en {@code
 * PurchaseReceiptApiTest}).
 * <p><b>Barrera:</b> {@link CountDownLatch} doble (ready/start), igual
 * patrón que {@code SaleConcurrencyTest}.
 * <p><b>Resultado permitido:</b> exactamente una recepción se confirma
 * (recibe 6, pendiente queda en 4); la otra se rechaza con 422
 * {@code CANTIDAD_RECEPCION_EXCEDE_ORDENADO} — nunca las dos, porque 6+6=12
 * excede los 10 ordenados.
 * <p><b>Invariantes:</b> {@code quantityReceived} final de la línea nunca
 * excede {@code quantityOrdered} (el {@code CHECK} de base de datos lo
 * respalda); el costo promedio ponderado del inventario resultante
 * corresponde exactamente a la única recepción aplicada.
 * <p><b>Evidencia en InventoryMovement:</b> exactamente un movimiento
 * {@code COMPRA}, cantidad 6, enlazado a la línea de origen — nunca dos.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PurchaseReceiptConcurrencyTest {

    @Autowired
    private PurchaseReceiptService purchaseReceiptService;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryMovementRepository movementRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @Autowired
    private ProductUnitRepository productUnitRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void twoConcurrentReceiptsOfTheSameLineOnlyOneSucceedsWithinPending() throws Exception {
        Branch branch = branchRepository.save(new Branch("SUC-CCPR", "Sucursal Concurrencia Recepción", null));
        User operator = userRepository.save(new User(
                "Operador CC Recepción", "operator.cc.receipt@test.local", passwordEncoder.encode("ChangeMe123!"), RoleCode.OPERATOR, branch.getId()));
        Supplier supplier = supplierRepository.save(new Supplier("Proveedor CC", "900123456-CC", null, null, null));
        UnitOfMeasure unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-CCPR", "Unidad"));
        Product product = productRepository.save(new Product("SKU-CCPR-001", "Producto Concurrencia Recepción", null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));

        PurchaseOrder order = purchaseOrderRepository.save(
                new PurchaseOrder("OC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), supplier.getId(), branch.getId(), null, operator.getId()));
        PurchaseOrderItem item = purchaseOrderItemRepository.save(new PurchaseOrderItem(
                order.getId(), product.getId(), unit.getId(), BigDecimal.TEN, new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("100.00")));

        AuthenticatedUser principal = new AuthenticatedUser(operator.getId(), operator.getName(), operator.getEmail(), RoleCode.OPERATOR, branch.getId());

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Exception>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            String idempotencyKey = UUID.randomUUID().toString();
            futures.add(executor.submit(() -> {
                authenticateAs(principal);
                try {
                    ready.countDown();
                    start.await();
                    purchaseReceiptService.receive(
                            order.getId(),
                            new PurchaseReceiptRequest(List.of(new ReceiptItemRequest(item.getId(), new BigDecimal("6"), new BigDecimal("10.00")))),
                            idempotencyKey,
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
                assertThat(((BusinessRuleViolationException) outcome).getCode()).isEqualTo("CANTIDAD_RECEPCION_EXCEDE_ORDENADO");
                businessFailures++;
            }
        }
        executor.shutdown();

        assertThat(successes).as("exactamente una recepción debe confirmarse").isEqualTo(1);
        assertThat(businessFailures).as("la otra debe rechazarse por exceder lo pendiente").isEqualTo(1);

        PurchaseOrderItem finalItem = purchaseOrderItemRepository.findById(item.getId()).orElseThrow();
        assertThat(finalItem.getQuantityReceived()).isEqualByComparingTo(new BigDecimal("6"));
        assertThat(finalItem.pending()).isEqualByComparingTo(new BigDecimal("4"));

        BigDecimal finalStock = inventoryRepository.findByProductIdAndBranchId(product.getId(), branch.getId())
                .orElseThrow().getQuantityOnHand();
        assertThat(finalStock).as("el stock refleja exactamente la única recepción aplicada").isEqualByComparingTo(new BigDecimal("6"));

        List<InventoryMovement> purchaseMovements = movementRepository.findAll().stream()
                .filter(m -> m.getReason() == MovementReason.COMPRA && m.getPurchaseOrderItemId() != null && m.getPurchaseOrderItemId().equals(item.getId()))
                .toList();
        assertThat(purchaseMovements)
                .as("exactamente un InventoryMovement de COMPRA para esta línea, nunca dos")
                .hasSize(1);
        assertThat(purchaseMovements.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("6"));
    }

    private void authenticateAs(AuthenticatedUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
