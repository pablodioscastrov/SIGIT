package com.inventario.multisucursal.dashboard;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.inventory.InventoryAdjustmentRequest;
import com.inventario.multisucursal.inventory.InventoryMovementService;
import com.inventario.multisucursal.inventory.InventoryRepository;
import com.inventario.multisucursal.inventory.MovementDirection;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.products.ProductUnit;
import com.inventario.multisucursal.products.ProductUnitRepository;
import com.inventario.multisucursal.products.UnitOfMeasure;
import com.inventario.multisucursal.products.UnitOfMeasureRepository;
import com.inventario.multisucursal.sales.Sale;
import com.inventario.multisucursal.sales.SaleItem;
import com.inventario.multisucursal.sales.SaleItemRepository;
import com.inventario.multisucursal.sales.SaleRepository;
import com.inventario.multisucursal.transfers.ApproveTransferItemRequest;
import com.inventario.multisucursal.transfers.ApproveTransferRequest;
import com.inventario.multisucursal.transfers.CreateTransferItemRequest;
import com.inventario.multisucursal.transfers.CreateTransferRequest;
import com.inventario.multisucursal.transfers.DispatchTransferItemRequest;
import com.inventario.multisucursal.transfers.DispatchTransferRequest;
import com.inventario.multisucursal.transfers.ReceiveTransferItemRequest;
import com.inventario.multisucursal.transfers.ReceiveTransferRequest;
import com.inventario.multisucursal.transfers.TransferItemRepository;
import com.inventario.multisucursal.transfers.TransferRepository;
import com.inventario.multisucursal.transfers.TransferResponse;
import com.inventario.multisucursal.transfers.TransferService;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.users.User;
import com.inventario.multisucursal.users.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dashboard (RF-031 a RF-035; BR-039 a BR-043). Se siembra directamente por
 * repositorio —no por el flujo HTTP completo de cada módulo— porque lo que se
 * prueba aquí es la <b>agregación</b>, no las reglas de creación de venta o
 * transferencia (ya cubiertas en sus propios módulos); eso permite datasets
 * pequeños y fechas exactas y conocidas, incluyendo backdatear
 * {@code Sale.saleDate} vía {@link JdbcTemplate} — el modelo aprobado no
 * expone un setter para eso (se fija en el constructor a "ahora"), y tampoco
 * existe un endpoint de escritura para {@code Inventory.minimum_stock}
 * (limitación conocida, documentada en {@code docs/STATUS.md}) — ambos casos
 * son, por diseño, imposibles de sembrar solo con la capa de servicio.
 *
 * <p>Las transferencias sí se crean con {@link TransferService} real
 * (autenticando al principal manualmente, mismo patrón que
 * {@code TransferRollbackTest}): su máquina de estados ya está probada en su
 * propio módulo, así que aquí conviene reutilizarla en vez de fabricar filas
 * con un estado inconsistente a mano.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class DashboardApiTest {

    private static final String SEED_PASSWORD = "ChangeMe123!";

    @Autowired
    private TestRestTemplate restTemplate;

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
    private SaleRepository saleRepository;

    @Autowired
    private SaleItemRepository saleItemRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferItemRepository transferItemRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Branch branchA;
    private Branch branchB;
    private User adminUser;
    private User managerUser;
    private UnitOfMeasure unit;
    private String adminToken;
    private String managerToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        transferItemRepository.deleteAll();
        transferRepository.deleteAll();
        inventoryRepository.deleteAll();
        productUnitRepository.deleteAll();
        productRepository.deleteAll();
        unitOfMeasureRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();

        branchA = branchRepository.save(new Branch("SUC-DB-A", "Sucursal A", null));
        branchB = branchRepository.save(new Branch("SUC-DB-B", "Sucursal B", null));

        String hash = passwordEncoder.encode(SEED_PASSWORD);
        adminUser = userRepository.save(new User("Admin", "admin.db@test.local", hash, RoleCode.ADMIN, null));
        managerUser = userRepository.save(new User("Gerente A", "manager.db@test.local", hash, RoleCode.MANAGER, branchA.getId()));
        userRepository.save(new User("Operador A", "operator.db@test.local", hash, RoleCode.OPERATOR, branchA.getId()));

        adminToken = login("admin.db@test.local");
        managerToken = login("manager.db@test.local");
        operatorToken = login("operator.db@test.local");

        unit = unitOfMeasureRepository.save(new UnitOfMeasure("UN-DB", "Unidad"));
    }

    /**
     * {@code authenticateAs} deja una autenticación en el
     * {@code SecurityContextHolder} (un {@code ThreadLocal}) para poder
     * llamar a {@code TransferService}/{@code InventoryMovementService}
     * directamente, sin pasar por el filtro JWT. Sin limpiarlo aquí, esa
     * autenticación sobrevive al final del método de prueba y puede
     * filtrarse a la siguiente clase de test que corra en el mismo hilo
     * —así se detectó, rompiendo {@code AuditableEntityTest}, que espera un
     * contexto sin autenticar—.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- BR-039 / RF-031: ventas del mes vs. anteriores ----

    @Test
    void salesSummaryAggregatesCurrentMonthAndFillsEmptyPreviousMonths() {
        seedSale(branchA.getId(), new BigDecimal("100.00"), Instant.now());
        seedSale(branchA.getId(), new BigDecimal("50.00"), Instant.now());

        SalesTrendResponse response = getSalesSummary(adminToken, branchA.getId(), null);

        assertThat(response.currentMonth().totalSales()).isEqualByComparingTo("150.00");
        assertThat(response.currentMonth().salesCount()).isEqualTo(2);
        assertThat(response.previousMonths()).hasSize(3);
        assertThat(response.previousMonths()).allSatisfy(month -> {
            assertThat(month.totalSales()).as("un mes sin ventas se muestra en 0, no se omite").isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(month.salesCount()).isZero();
        });
        assertThat(response.growthVsPreviousMonthPercentage())
                .as("el mes anterior no tuvo ventas: la variación no es calculable")
                .isNull();
    }

    @Test
    void salesSummaryComputesGrowthAgainstPreviousMonth() {
        seedSale(branchA.getId(), new BigDecimal("150.00"), Instant.now());
        seedSale(branchA.getId(), new BigDecimal("80.00"), startOfCurrentMonth().minus(java.time.Duration.ofDays(5)));

        SalesTrendResponse response = getSalesSummary(adminToken, branchA.getId(), null);

        assertThat(response.currentMonth().totalSales()).isEqualByComparingTo("150.00");
        SalesTrendResponse.MonthlySales lastMonth = response.previousMonths().get(response.previousMonths().size() - 1);
        assertThat(lastMonth.totalSales()).isEqualByComparingTo("80.00");
        // (150 - 80) / 80 * 100 = 87.50
        assertThat(response.growthVsPreviousMonthPercentage()).isEqualByComparingTo("87.50");
    }

    @Test
    void salesSummarySaleExactlyAtMonthBoundaryCountsInTheRightMonth() {
        Instant boundary = startOfCurrentMonth();
        seedSale(branchA.getId(), new BigDecimal("10.00"), boundary); // límite inferior inclusivo del mes actual
        seedSale(branchA.getId(), new BigDecimal("20.00"), boundary.minusMillis(1)); // último instante del mes anterior

        SalesTrendResponse response = getSalesSummary(adminToken, branchA.getId(), null);

        assertThat(response.currentMonth().totalSales()).isEqualByComparingTo("10.00");
        SalesTrendResponse.MonthlySales lastMonth = response.previousMonths().get(response.previousMonths().size() - 1);
        assertThat(lastMonth.totalSales()).isEqualByComparingTo("20.00");
    }

    @Test
    void salesSummaryRequiresBranchId() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/dashboard/sales-summary", HttpMethod.GET, authorized(adminToken), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(response)).isEqualTo("SUCURSAL_REQUERIDA");
    }

    @Test
    void salesSummaryRejectsForeignBranchForOperator() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/dashboard/sales-summary?branchId=" + branchB.getId(), HttpMethod.GET, authorized(operatorToken), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(response)).isEqualTo("SUCURSAL_NO_AUTORIZADA");
    }

    @Test
    void salesSummaryAllowsManagerToQueryAnyBranch() {
        SalesTrendResponse response = getSalesSummary(managerToken, branchB.getId(), null);

        assertThat(response.branchId()).isEqualTo(String.valueOf(branchB.getId()));
    }

    // ---- BR-040 / RF-032: rotación y demanda alta/baja ----

    @Test
    void inventoryRotationRanksHighAndLowDemandWithTurnoverRatio() {
        Product high = seedProduct("SKU-DB-HIGH");
        Product mid = seedProduct("SKU-DB-MID");
        Product zero = seedProduct("SKU-DB-ZERO");
        seedInventory(high.getId(), branchA.getId(), new BigDecimal("10"));
        seedInventory(mid.getId(), branchA.getId(), new BigDecimal("50"));
        seedInventory(zero.getId(), branchA.getId(), new BigDecimal("5"));

        Sale sale = seedSale(branchA.getId(), new BigDecimal("0"), Instant.now());
        seedSaleItem(sale.getId(), high.getId(), new BigDecimal("20"));
        seedSaleItem(sale.getId(), mid.getId(), new BigDecimal("5"));

        InventoryDemandResponse response = getInventoryRotation(adminToken, branchA.getId(), null, 2);

        assertThat(response.topDemand()).hasSize(2);
        assertThat(response.topDemand().get(0).sku()).isEqualTo("SKU-DB-HIGH");
        assertThat(response.topDemand().get(0).unitsSold()).isEqualByComparingTo("20");
        assertThat(response.topDemand().get(0).turnoverRatio()).isEqualByComparingTo("2.0000");
        assertThat(response.topDemand().get(1).sku()).isEqualTo("SKU-DB-MID");

        assertThat(response.lowDemand()).hasSize(2);
        assertThat(response.lowDemand().get(0).sku())
                .as("0 ventas en la ventana es la señal más clara de baja demanda")
                .isEqualTo("SKU-DB-ZERO");
        assertThat(response.lowDemand().get(0).unitsSold()).isEqualByComparingTo("0");
        assertThat(response.lowDemand().get(0).turnoverRatio()).isEqualByComparingTo("0.0000");
    }

    @Test
    void inventoryRotationTurnoverIsNullWhenCurrentStockIsZero() {
        Product product = seedProduct("SKU-DB-NOSTOCK");
        seedInventory(product.getId(), branchA.getId(), BigDecimal.ZERO);
        Sale sale = seedSale(branchA.getId(), BigDecimal.ZERO, Instant.now());
        seedSaleItem(sale.getId(), product.getId(), new BigDecimal("3"));

        InventoryDemandResponse response = getInventoryRotation(adminToken, branchA.getId(), null, 5);

        InventoryDemandResponse.ProductDemandEntry entry = response.topDemand().stream()
                .filter(e -> e.sku().equals("SKU-DB-NOSTOCK")).findFirst().orElseThrow();
        assertThat(entry.turnoverRatio()).as("stock 0 no es una división por cero ni Infinity, es no calculable").isNull();
    }

    @Test
    void inventoryRotationEmptyBranchReturnsEmptyLists() {
        InventoryDemandResponse response = getInventoryRotation(adminToken, branchB.getId(), null, 5);

        assertThat(response.topDemand()).isEmpty();
        assertThat(response.lowDemand()).isEmpty();
    }

    @Test
    void inventoryRotationRejectsInvalidLimit() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/dashboard/inventory-rotation?branchId=" + branchA.getId() + "&limit=0",
                HttpMethod.GET, authorized(adminToken), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorCode(response)).isEqualTo("PARAMETRO_INVALIDO");
    }

    // ---- BR-041 / RF-033: transferencias activas e impacto ----

    @Test
    void activeTransfersComputesInTransitAndPendingDispatchSeparately() {
        Product product = seedProduct("SKU-DB-TR1");
        seedInventory(product.getId(), branchB.getId(), new BigDecimal("100")); // branchB es el origen de estas transferencias

        // IN_TRANSIT: aprobado 10, despachado 7, recibido 3 -> en tránsito real = 4.
        TransferResponse t1 = createTransfer(product.getId(), new BigDecimal("10"));
        approveTransfer(t1, new BigDecimal("10"));
        dispatchTransfer(t1, new BigDecimal("7"));
        receiveTransfer(t1, new BigDecimal("3"));

        // APPROVED sin despachar: pendiente proyectado = 8, nada en tránsito todavía.
        TransferResponse t2 = createTransfer(product.getId(), new BigDecimal("8"));
        approveTransfer(t2, new BigDecimal("8"));

        // REQUESTED: pendiente proyectado usa lo solicitado (aún no hay aprobado).
        createTransfer(product.getId(), new BigDecimal("5"));

        ActiveTransfersResponse response = getActiveTransfers(adminToken, branchA.getId());

        assertThat(response.activeCount()).isEqualTo(3);
        assertThat(response.totalUnitsInTransit()).isEqualByComparingTo("4");
        assertThat(response.totalUnitsPendingDispatch()).isEqualByComparingTo("13");
    }

    @Test
    void activeTransfersExcludeRejectedAndIncludeReceivedPartial() {
        Product product = seedProduct("SKU-DB-TR2");
        seedInventory(product.getId(), branchB.getId(), new BigDecimal("100")); // branchB es el origen de estas transferencias

        TransferResponse rejected = createTransfer(product.getId(), new BigDecimal("4"));
        authenticateAs(adminUser.getId(), RoleCode.ADMIN, null);
        transferService.reject(Long.valueOf(rejected.id()), adminUser.getId());

        TransferResponse partial = createTransfer(product.getId(), new BigDecimal("10"));
        approveTransfer(partial, new BigDecimal("10"));
        dispatchTransfer(partial, new BigDecimal("10"));
        receiveTransfer(partial, new BigDecimal("6")); // deja faltante -> RECEIVED_PARTIAL

        ActiveTransfersResponse response = getActiveTransfers(adminToken, branchA.getId());

        assertThat(response.transfers()).extracting(ActiveTransfersResponse.ActiveTransferEntry::transferId)
                .doesNotContain(rejected.id())
                .contains(partial.id());
    }

    @Test
    void activeTransfersEmptyWhenNoneActive() {
        ActiveTransfersResponse response = getActiveTransfers(adminToken, branchB.getId());

        assertThat(response.activeCount()).isZero();
        assertThat(response.transfers()).isEmpty();
        assertThat(response.totalUnitsInTransit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- BR-042 / RF-034: reabastecimiento ----

    @Test
    void replenishmentOrdersMostUrgentFirstAndCounts() {
        Product veryLow = seedProduct("SKU-DB-REP1");
        Product barelyLow = seedProduct("SKU-DB-REP2");
        Product notLow = seedProduct("SKU-DB-REP3");
        seedInventoryWithMinimum(veryLow.getId(), branchA.getId(), new BigDecimal("2"), new BigDecimal("10")); // -8
        seedInventoryWithMinimum(barelyLow.getId(), branchA.getId(), new BigDecimal("8"), new BigDecimal("10")); // -2
        seedInventoryWithMinimum(notLow.getId(), branchA.getId(), new BigDecimal("20"), new BigDecimal("5")); // no está bajo

        ReplenishmentResponse response = getReplenishment(adminToken, branchA.getId(), 5);

        assertThat(response.lowStockCount()).isEqualTo(2);
        assertThat(response.mostUrgent()).extracting(ReplenishmentResponse.ReplenishmentEntry::sku)
                .containsExactly("SKU-DB-REP1", "SKU-DB-REP2");
    }

    @Test
    void replenishmentEmptyWhenNothingBelowThreshold() {
        Product product = seedProduct("SKU-DB-REP4");
        seedInventoryWithMinimum(product.getId(), branchA.getId(), new BigDecimal("50"), new BigDecimal("5"));

        ReplenishmentResponse response = getReplenishment(adminToken, branchA.getId(), 5);

        assertThat(response.lowStockCount()).isZero();
        assertThat(response.mostUrgent()).isEmpty();
    }

    // ---- BR-043 / RF-035: comparativa entre sucursales ----

    @Test
    void branchComparisonIsDeniedForOperator() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/dashboard/branch-comparison", HttpMethod.GET, authorized(operatorToken), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(response)).isEqualTo("ROL_NO_AUTORIZADO");
    }

    @Test
    void branchComparisonListsEveryActiveBranchIncludingOnesWithoutData() {
        seedSale(branchA.getId(), new BigDecimal("300.00"), Instant.now());

        ResponseEntity<BranchComparisonResponse> response = restTemplate.exchange(
                "/api/v1/dashboard/branch-comparison", HttpMethod.GET, authorized(managerToken), BranchComparisonResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BranchComparisonResponse.BranchMetrics metricsA = findBranch(response.getBody(), branchA.getId());
        BranchComparisonResponse.BranchMetrics metricsB = findBranch(response.getBody(), branchB.getId());

        assertThat(metricsA.currentMonthSales()).isEqualByComparingTo("300.00");
        assertThat(metricsB.currentMonthSales())
                .as("una sucursal sin ventas aparece con 0, no se omite")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- helpers ----

    private BranchComparisonResponse.BranchMetrics findBranch(BranchComparisonResponse response, Long branchId) {
        return response.branches().stream()
                .filter(b -> b.branchId().equals(String.valueOf(branchId)))
                .findFirst().orElseThrow();
    }

    private Instant startOfCurrentMonth() {
        return YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Sale seedSale(Long branchId, BigDecimal total, Instant saleDate) {
        String saleNumber = "V-DB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Sale sale = saleRepository.save(new Sale(saleNumber, branchId, adminUser.getId(), 1L, UUID.randomUUID().toString()));
        sale.updateTotals(total, BigDecimal.ZERO, total);
        sale = saleRepository.save(sale);
        jdbcTemplate.update("UPDATE sale SET sale_date = ? WHERE id = ?", toTimestamp(saleDate), sale.getId());
        return sale;
    }

    private void seedSaleItem(Long saleId, Long productId, BigDecimal quantity) {
        saleItemRepository.save(new SaleItem(saleId, productId, unit.getId(), quantity, BigDecimal.ONE, BigDecimal.ZERO, quantity));
    }

    private Product seedProduct(String sku) {
        Product product = productRepository.save(new Product(sku, "Producto " + sku, null, unit.getId()));
        productUnitRepository.save(new ProductUnit(product.getId(), unit.getId(), BigDecimal.ONE, true));
        return product;
    }

    /**
     * Vía el servicio real (no {@code inventoryRepository.applyQuantity}
     * directo): ese método es un {@code @Modifying} que exige una
     * transacción activa que confirme antes de que la petición HTTP de
     * {@code TestRestTemplate} —en otro hilo/conexión— pueda ver el dato;
     * {@code InventoryMovementService.createAdjustment} ya resuelve eso
     * (mismo patrón que {@code TransferRollbackTest}), y además crea la fila
     * de {@code Inventory} si no existe.
     */
    private void seedInventory(Long productId, Long branchId, BigDecimal quantityOnHand) {
        authenticateAs(adminUser.getId(), RoleCode.ADMIN, null);
        // Un ajuste en 0 se rechaza (CANTIDAD_INVALIDA): para dejar el saldo
        // en 0 pero con la fila de Inventory ya creada (necesaria para que el
        // producto siga siendo candidato a "baja demanda"), se ingresa un
        // placeholder y se retira de vuelta.
        BigDecimal seedAmount = quantityOnHand.compareTo(BigDecimal.ZERO) > 0 ? quantityOnHand : BigDecimal.ONE;
        inventoryMovementService.createAdjustment(
                new InventoryAdjustmentRequest(branchId, productId, null, MovementDirection.INGRESO, null, seedAmount, "Siembra de prueba"),
                adminUser.getId());
        if (quantityOnHand.compareTo(BigDecimal.ZERO) == 0) {
            inventoryMovementService.createAdjustment(
                    new InventoryAdjustmentRequest(branchId, productId, null, MovementDirection.RETIRO, null, seedAmount, "Siembra de prueba"),
                    adminUser.getId());
        }
    }

    private void seedInventoryWithMinimum(Long productId, Long branchId, BigDecimal quantityOnHand, BigDecimal minimumStock) {
        seedInventory(productId, branchId, quantityOnHand);
        jdbcTemplate.update("UPDATE inventory SET minimum_stock = ? WHERE product_id = ? AND branch_id = ?", minimumStock, productId, branchId);
    }

    private TransferResponse createTransfer(Long productId, BigDecimal quantity) {
        authenticateAs(managerUser.getId(), RoleCode.MANAGER, branchA.getId());
        return transferService.request(
                new CreateTransferRequest(branchB.getId(), branchA.getId(), false, List.of(new CreateTransferItemRequest(productId, quantity))),
                managerUser.getId(), UUID.randomUUID().toString());
    }

    // Aprobar/despachar exigen pertenecer a la sucursal ORIGEN (branchB en
    // este test); recibir/solicitar exigen la sucursal DESTINO (branchA). El
    // principal se fabrica con el branchId que corresponda a cada paso —
    // AuthorizationService solo mira este objeto, no vuelve a consultar el
    // usuario real en la base de datos.
    private void approveTransfer(TransferResponse transfer, BigDecimal quantity) {
        authenticateAs(managerUser.getId(), RoleCode.MANAGER, branchB.getId());
        Long itemId = Long.valueOf(transfer.items().get(0).id());
        transferService.approve(Long.valueOf(transfer.id()), new ApproveTransferRequest(List.of(new ApproveTransferItemRequest(itemId, quantity))), managerUser.getId());
    }

    private void dispatchTransfer(TransferResponse transfer, BigDecimal quantity) {
        authenticateAs(managerUser.getId(), RoleCode.MANAGER, branchB.getId());
        Long itemId = Long.valueOf(transfer.items().get(0).id());
        transferService.dispatch(Long.valueOf(transfer.id()), new DispatchTransferRequest(null, null, List.of(new DispatchTransferItemRequest(itemId, quantity))), managerUser.getId());
    }

    private void receiveTransfer(TransferResponse transfer, BigDecimal quantity) {
        authenticateAs(managerUser.getId(), RoleCode.MANAGER, branchA.getId());
        Long itemId = Long.valueOf(transfer.items().get(0).id());
        transferService.receive(Long.valueOf(transfer.id()), new ReceiveTransferRequest(List.of(new ReceiveTransferItemRequest(itemId, quantity))), managerUser.getId());
    }

    private void authenticateAs(Long userId, RoleCode role, Long branchId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "Test", "test@test.local", role, branchId);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private SalesTrendResponse getSalesSummary(String token, Long branchId, Integer months) {
        String url = "/api/v1/dashboard/sales-summary?branchId=" + branchId + (months != null ? "&months=" + months : "");
        ResponseEntity<SalesTrendResponse> response = restTemplate.exchange(url, HttpMethod.GET, authorized(token), SalesTrendResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private InventoryDemandResponse getInventoryRotation(String token, Long branchId, Integer months, Integer limit) {
        String url = "/api/v1/dashboard/inventory-rotation?branchId=" + branchId
                + (months != null ? "&months=" + months : "") + (limit != null ? "&limit=" + limit : "");
        ResponseEntity<InventoryDemandResponse> response = restTemplate.exchange(url, HttpMethod.GET, authorized(token), InventoryDemandResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ActiveTransfersResponse getActiveTransfers(String token, Long branchId) {
        ResponseEntity<ActiveTransfersResponse> response = restTemplate.exchange(
                "/api/v1/dashboard/active-transfers?branchId=" + branchId, HttpMethod.GET, authorized(token), ActiveTransfersResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ReplenishmentResponse getReplenishment(String token, Long branchId, Integer limit) {
        String url = "/api/v1/dashboard/replenishment?branchId=" + branchId + (limit != null ? "&limit=" + limit : "");
        ResponseEntity<ReplenishmentResponse> response = restTemplate.exchange(url, HttpMethod.GET, authorized(token), ReplenishmentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpEntity<Void> authorized(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private String login(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("email", email, "password", SEED_PASSWORD), headers);
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/login", request, Map.class);
        return (String) response.getBody().get("accessToken");
    }

    private String errorCode(ResponseEntity<Map> response) {
        Map<?, ?> error = (Map<?, ?>) response.getBody().get("error");
        return (String) error.get("code");
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }
}
