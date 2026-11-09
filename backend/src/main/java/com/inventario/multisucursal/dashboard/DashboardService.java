package com.inventario.multisucursal.dashboard;

import com.inventario.multisucursal.auth.AuthenticatedUser;
import com.inventario.multisucursal.branches.Branch;
import com.inventario.multisucursal.branches.BranchRepository;
import com.inventario.multisucursal.common.exception.BadRequestException;
import com.inventario.multisucursal.common.exception.BranchAccessDeniedException;
import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.users.RoleCode;
import com.inventario.multisucursal.inventory.Inventory;
import com.inventario.multisucursal.inventory.InventoryService;
import com.inventario.multisucursal.products.Product;
import com.inventario.multisucursal.products.ProductRepository;
import com.inventario.multisucursal.sales.ProductDemand;
import com.inventario.multisucursal.sales.SaleService;
import com.inventario.multisucursal.sales.SalesAggregate;
import com.inventario.multisucursal.transfers.ActiveTransferImpact;
import com.inventario.multisucursal.transfers.TransferService;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agregación de indicadores para el dashboard (RF-031 a RF-035;
 * BR-039 a BR-043). Hoja del grafo de dependencias, igual que {@code reports}
 * (docs/ARCHITECTURE.md, sección 4): solo lee, nunca escribe, a través de las
 * capas de servicio de {@code sales}, {@code inventory} y {@code transfers} —
 * nunca de sus repositorios — y ningún otro módulo depende de este.
 *
 * <p>Cada consulta de agregación pesada (sumas, conteos, top-N) ocurre en SQL
 * dentro del módulo dueño del dato; aquí solo se combinan resultados ya
 * acotados (una fila por mes, un puñado de productos, una sucursal a la vez)
 * — nunca se carga una tabla completa para sumarla o para ordenarla en Java.
 */
@Service
public class DashboardService {

    private static final int DEFAULT_MONTHS_BACK = 3;
    private static final int MAX_MONTHS_BACK = 24;
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 50;
    private static final int PERCENTAGE_SCALE = 2;
    private static final int RATIO_SCALE = 4;

    private final SaleService saleService;
    private final InventoryService inventoryService;
    private final TransferService transferService;
    private final ProductRepository productRepository;
    private final BranchRepository branchRepository;

    public DashboardService(
            SaleService saleService,
            InventoryService inventoryService,
            TransferService transferService,
            ProductRepository productRepository,
            BranchRepository branchRepository) {
        this.saleService = saleService;
        this.inventoryService = inventoryService;
        this.transferService = transferService;
        this.productRepository = productRepository;
        this.branchRepository = branchRepository;
    }

    // ---- RF-031 ----

    public SalesTrendResponse salesTrend(Long branchId, Integer monthsParam) {
        Branch branch = requireBranch(branchId);
        int monthsBack = normalizeMonths(monthsParam);
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);

        List<SalesTrendResponse.MonthlySales> previousMonths = new ArrayList<>();
        for (int monthsAgo = monthsBack; monthsAgo >= 1; monthsAgo--) {
            previousMonths.add(monthBucket(branch.getId(), currentMonth.minusMonths(monthsAgo)));
        }
        SalesTrendResponse.MonthlySales current = monthBucket(branch.getId(), currentMonth);

        BigDecimal previousTotal = previousMonths.isEmpty() ? null : previousMonths.get(previousMonths.size() - 1).totalSales();
        BigDecimal growth = percentageChange(current.totalSales(), previousTotal);

        return new SalesTrendResponse(String.valueOf(branch.getId()), branch.getName(), current, previousMonths, growth);
    }

    private SalesTrendResponse.MonthlySales monthBucket(Long branchId, YearMonth month) {
        Instant from = startOfMonth(month);
        Instant to = startOfMonth(month.plusMonths(1));
        SalesAggregate aggregate = saleService.salesTotals(branchId, from, to);
        return new SalesTrendResponse.MonthlySales(month.toString(), aggregate.totalSales(), aggregate.salesCount());
    }

    private BigDecimal percentageChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }

    // ---- RF-032 ----

    public InventoryDemandResponse inventoryDemand(Long branchId, Integer monthsParam, Integer limitParam) {
        Branch branch = requireBranch(branchId);
        int monthsBack = normalizeMonths(monthsParam);
        int limit = normalizeLimit(limitParam);
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        Instant from = startOfMonth(currentMonth.minusMonths(monthsBack));
        Instant to = startOfMonth(currentMonth.plusMonths(1));

        List<Long> productIds = inventoryService.productIdsInBranch(branch.getId());
        if (productIds.isEmpty()) {
            return new InventoryDemandResponse(String.valueOf(branch.getId()), branch.getName(), from.toString(), to.toString(), List.of(), List.of());
        }

        // Se parte del catálogo de la sucursal (acotado a su inventario, no al
        // histórico de ventas) para que un producto con 0 ventas en la
        // ventana siga apareciendo como candidato a "baja demanda" — BR-040.
        Map<Long, BigDecimal> unitsSoldByProduct = productIds.stream()
                .collect(Collectors.toMap(id -> id, id -> BigDecimal.ZERO, (a, b) -> a, LinkedHashMap::new));
        for (ProductDemand demand : saleService.demandByProduct(branch.getId(), from, to)) {
            if (unitsSoldByProduct.containsKey(demand.productId())) {
                unitsSoldByProduct.put(demand.productId(), demand.unitsSold());
            }
        }

        List<Map.Entry<Long, BigDecimal>> ascending = unitsSoldByProduct.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .toList();
        List<Long> lowIds = ascending.stream().limit(limit).map(Map.Entry::getKey).toList();
        List<Long> topIds = ascending.reversed().stream().limit(limit).map(Map.Entry::getKey).toList();

        Set<Long> neededIds = new LinkedHashSet<>();
        neededIds.addAll(topIds);
        neededIds.addAll(lowIds);
        Map<Long, Product> productsById = productRepository.findAllById(neededIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        Map<Long, Inventory> stockById = inventoryService.currentStock(branch.getId(), neededIds).stream()
                .collect(Collectors.toMap(Inventory::getProductId, i -> i));

        List<InventoryDemandResponse.ProductDemandEntry> topDemand = topIds.stream()
                .map(id -> demandEntry(id, unitsSoldByProduct.get(id), productsById.get(id), stockById.get(id)))
                .toList();
        List<InventoryDemandResponse.ProductDemandEntry> lowDemand = lowIds.stream()
                .map(id -> demandEntry(id, unitsSoldByProduct.get(id), productsById.get(id), stockById.get(id)))
                .toList();

        return new InventoryDemandResponse(String.valueOf(branch.getId()), branch.getName(), from.toString(), to.toString(), topDemand, lowDemand);
    }

    private InventoryDemandResponse.ProductDemandEntry demandEntry(Long productId, BigDecimal unitsSold, Product product, Inventory inventory) {
        BigDecimal currentStock = inventory != null ? inventory.getQuantityOnHand() : BigDecimal.ZERO;
        BigDecimal turnover = currentStock.compareTo(BigDecimal.ZERO) > 0
                ? unitsSold.divide(currentStock, RATIO_SCALE, RoundingMode.HALF_UP)
                : null;
        return new InventoryDemandResponse.ProductDemandEntry(
                String.valueOf(productId),
                product != null ? product.getSku() : null,
                product != null ? product.getName() : null,
                unitsSold, currentStock, turnover);
    }

    // ---- RF-033 ----

    public ActiveTransfersResponse activeTransfers(Long branchId) {
        Branch branch = requireBranch(branchId);
        List<ActiveTransferImpact> impacts = transferService.findActiveForDashboard(branch.getId());

        BigDecimal totalInTransit = impacts.stream().map(ActiveTransferImpact::unitsInTransit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPending = impacts.stream().map(ActiveTransferImpact::unitsPendingDispatch)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ActiveTransfersResponse.ActiveTransferEntry> entries = impacts.stream()
                .map(impact -> new ActiveTransfersResponse.ActiveTransferEntry(
                        String.valueOf(impact.transferId()), impact.transferNumber(), impact.status(),
                        String.valueOf(impact.originBranchId()), String.valueOf(impact.destinationBranchId()),
                        impact.urgency(), impact.unitsInTransit(), impact.unitsPendingDispatch()))
                .toList();

        return new ActiveTransfersResponse(String.valueOf(branch.getId()), branch.getName(), impacts.size(), totalInTransit, totalPending, entries);
    }

    // ---- RF-034 ----

    public ReplenishmentResponse replenishment(Long branchId, Integer limitParam) {
        Branch branch = requireBranch(branchId);
        int limit = normalizeLimit(limitParam);

        long lowStockCount = inventoryService.countLowStock(branch.getId());
        List<Inventory> urgent = inventoryService.mostUrgentLowStock(branch.getId(), limit);
        Map<Long, Product> productsById = productRepository.findAllById(urgent.stream().map(Inventory::getProductId).toList()).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        List<ReplenishmentResponse.ReplenishmentEntry> entries = urgent.stream()
                .map(inventory -> {
                    Product product = productsById.get(inventory.getProductId());
                    return new ReplenishmentResponse.ReplenishmentEntry(
                            String.valueOf(inventory.getProductId()),
                            product != null ? product.getSku() : null,
                            product != null ? product.getName() : null,
                            inventory.getQuantityOnHand(), inventory.getMinimumStock());
                })
                .toList();

        return new ReplenishmentResponse(String.valueOf(branch.getId()), branch.getName(), lowStockCount, entries);
    }

    // ---- RF-035 ----

    public BranchComparisonResponse branchComparison() {
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        Instant from = startOfMonth(currentMonth);
        Instant to = startOfMonth(currentMonth.plusMonths(1));

        List<Branch> branches = branchRepository.search(true, Pageable.unpaged()).getContent();
        List<BranchComparisonResponse.BranchMetrics> metrics = new ArrayList<>();
        for (Branch branch : branches) {
            SalesAggregate sales = saleService.salesTotals(branch.getId(), from, to);
            long activeTransfersCount = transferService.findActiveForDashboard(branch.getId()).size();
            long lowStockCount = inventoryService.countLowStock(branch.getId());
            metrics.add(new BranchComparisonResponse.BranchMetrics(
                    String.valueOf(branch.getId()), branch.getName(), sales.totalSales(), activeTransfersCount, lowStockCount));
        }
        return new BranchComparisonResponse(metrics);
    }

    // ---- helpers ----

    /**
     * {@code branchId} obligatorio (BR-039/040/041/042): estos endpoints
     * reportan una sucursal a la vez. El control de acceso replica
     * {@code LogisticsComplianceService.resolveBranchScope} —no
     * {@code AuthorizationService.requireBranchAccess}, que restringiría
     * también a {@code MANAGER}—: solo {@code OPERATOR} queda limitado a la
     * suya; {@code MANAGER}/{@code ADMIN} consultan cualquiera, mismo
     * "dashboard completo" ya aprobado para el reporte de cumplimiento
     * logístico.
     */
    private Branch requireBranch(Long branchId) {
        if (branchId == null) {
            throw new BadRequestException("SUCURSAL_REQUERIDA", "El parámetro branchId es obligatorio.");
        }
        AuthenticatedUser user = (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (user.role() == RoleCode.OPERATOR && !branchId.equals(user.branchId())) {
            throw new BranchAccessDeniedException("Un operador solo puede consultar el dashboard de su propia sucursal.");
        }
        return branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("SUCURSAL_NO_ENCONTRADA", "Sucursal no encontrada."));
    }

    private int normalizeMonths(Integer months) {
        if (months == null) {
            return DEFAULT_MONTHS_BACK;
        }
        if (months < 0 || months > MAX_MONTHS_BACK) {
            throw new BusinessRuleViolationException("PARAMETRO_INVALIDO", "months debe estar entre 0 y " + MAX_MONTHS_BACK + ".");
        }
        return months;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new BusinessRuleViolationException("PARAMETRO_INVALIDO", "limit debe estar entre 1 y " + MAX_LIMIT + ".");
        }
        return limit;
    }

    private static Instant startOfMonth(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
