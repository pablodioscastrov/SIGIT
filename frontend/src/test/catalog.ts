import { jsonResponse } from "./harness";
import type {
  ComplianceMetrics,
  InventoryMovement,
  InventoryRow,
  LogisticsComplianceResponse,
  Price,
  PriceList,
  Product,
  ProductUnit,
  PurchaseOrder,
  RoleInfo,
  Route,
  Sale,
  StockAlert,
  Supplier,
  Transfer,
  UnitOfMeasure,
  User,
} from "../types/api";

/** Datos de catálogo compartidos por las pruebas de productos e inventario. */

export const UNITS: UnitOfMeasure[] = [
  { id: "1", code: "UND", name: "Unidad" },
  { id: "2", code: "CJA", name: "Caja" },
];

export const PRODUCTS: Product[] = [
  { id: "10", sku: "SKU-001", name: "Cemento gris", description: "Saco de 50 kg", baseUnitOfMeasureId: "1", active: true, minimumStock: 10, salePrice: 50 },
  { id: "11", sku: "SKU-002", name: "Arena fina", description: null, baseUnitOfMeasureId: "1", active: false, minimumStock: 0, salePrice: null },
];

export const PRODUCT_UNITS: ProductUnit[] = [
  { unitOfMeasureId: "1", unitCode: "UND", unitName: "Unidad", conversionFactorToBase: 1, baseUnit: true },
  { unitOfMeasureId: "2", unitCode: "CJA", unitName: "Caja", conversionFactorToBase: 12, baseUnit: false },
];

export const BRANCHES = [
  { id: "1", code: "SUC-001", name: "Sucursal Centro", location: "Centro", active: true },
  { id: "2", code: "SUC-002", name: "Sucursal Norte", location: "Norte", active: true },
];

export function inventoryRow(overrides: Partial<InventoryRow> = {}): InventoryRow {
  return {
    id: "100",
    productId: "10",
    branchId: "1",
    quantityOnHand: 5,
    averageUnitCost: 12.5,
    minimumStock: 10,
    updatedAt: "2026-08-27T10:00:00Z",
    ...overrides,
  };
}

export function movement(overrides: Partial<InventoryMovement> = {}): InventoryMovement {
  return {
    id: "900",
    productId: "10",
    branchId: "1",
    direction: "INGRESO",
    reason: "AJUSTE_INGRESO",
    quantity: 10,
    unitOfMeasureId: "1",
    responsibleUserId: "1",
    occurredAt: "2026-08-27T10:00:00Z",
    notes: "Conteo físico",
    source: null,
    ...overrides,
  };
}

export const SUPPLIERS: Supplier[] = [
  { id: "1", name: "Distribuidora Andina", taxId: "TAX-001", contactName: "Ana Ríos", phone: null, email: null, active: true },
  { id: "2", name: "Proveedor Norte", taxId: "TAX-002", contactName: null, phone: null, email: null, active: true },
];

export const PRICE_LISTS: PriceList[] = [
  { id: "1", name: "Lista Centro", branchId: "1", active: true },
  { id: "2", name: "Lista General", branchId: null, active: true },
];

export const PRICES: Price[] = [
  { id: "1", priceListId: "1", productId: "10", unitPrice: 50, validFrom: "2026-08-01T00:00:00Z", validTo: null },
];

export function purchaseOrder(overrides: Partial<PurchaseOrder> = {}): PurchaseOrder {
  return {
    id: "500",
    orderNumber: "OC-ABC12345",
    supplierId: "1",
    branchId: "1",
    status: "CREATED",
    orderDate: "2026-08-27T10:00:00Z",
    paymentTerm: "30 días",
    items: [
      {
        id: "5000",
        productId: "10",
        unitOfMeasureId: "1",
        quantityOrdered: 20,
        quantityReceived: 0,
        pending: 20,
        unitPrice: 15.5,
        discountPercentage: 0,
        lineTotal: 310,
      },
    ],
    ...overrides,
  };
}

export function sale(overrides: Partial<Sale> = {}): Sale {
  return {
    id: "700",
    saleNumber: "V-ABC12345",
    branchId: "1",
    soldByUserId: "1",
    soldByUserName: "Operador Centro",
    status: "CONFIRMED",
    saleDate: "2026-08-27T10:00:00Z",
    items: [
      { id: "9000", productId: "10", quantity: 3, unitOfMeasureId: "1", unitPrice: 50, discountPercentage: 0, lineTotal: 150, quantityReturned: 0, pending: 3 },
    ],
    subtotal: 150,
    discountTotal: 0,
    total: 150,
    ...overrides,
  };
}

export function transfer(overrides: Partial<Transfer> = {}): Transfer {
  return {
    id: "500",
    transferNumber: "TR-ABC12345",
    status: "REQUESTED",
    originBranchId: "1",
    destinationBranchId: "2",
    routeId: null,
    urgency: false,
    carrierName: null,
    estimatedArrivalDate: null,
    requestedByUserId: "1",
    approvedByUserId: null,
    requestedAt: "2026-08-27T10:00:00Z",
    approvedAt: null,
    dispatchedAt: null,
    receivedAt: null,
    items: [
      {
        id: "5000",
        productId: "10",
        unitOfMeasureId: "1",
        quantityRequested: 10,
        quantityApproved: null,
        quantityShipped: null,
        quantityReceived: null,
        quantityMissing: null,
        discrepancyTreatment: null,
        followUpTransferId: null,
        treatmentNotes: null,
      },
    ],
    ...overrides,
  };
}

export const ROUTES: Route[] = [{ id: "1", originBranchId: "1", destinationBranchId: "2", classification: "TIME" }];

export const ROLES: RoleInfo[] = [
  { code: "ADMIN", name: "Administrador general" },
  { code: "MANAGER", name: "Gerente de sucursal" },
  { code: "OPERATOR", name: "Operador de inventario" },
];

export const USERS: User[] = [
  { id: "1", name: "Admin General", email: "admin@inventario.local", role: "ADMIN", branchId: null, active: true, deactivationReason: null },
  { id: "2", name: "Gerente Centro", email: "gerente.centro@inventario.local", role: "MANAGER", branchId: "1", active: true, deactivationReason: null },
  {
    id: "3",
    name: "Operador Centro",
    email: "operador.centro@inventario.local",
    role: "OPERATOR",
    branchId: "1",
    active: false,
    deactivationReason: "Renuncia",
  },
];

function complianceMetrics(overrides: Partial<ComplianceMetrics> = {}): ComplianceMetrics {
  return {
    dispatched: 4,
    delivered: 3,
    inTransit: 1,
    overdueInTransit: 0,
    onTime: 2,
    late: 1,
    notEvaluable: 0,
    withShortages: 1,
    complianceRate: 66.67,
    averageDeliveryHours: 12.5,
    ...overrides,
  };
}

export function logisticsCompliance(overrides: Partial<LogisticsComplianceResponse> = {}): LogisticsComplianceResponse {
  return {
    appliedFilters: { branchId: null, routeId: null, dispatchedFrom: null, dispatchedTo: null },
    summary: complianceMetrics(),
    byRoute: [
      {
        routeId: "1",
        originBranchId: "1",
        destinationBranchId: "2",
        classification: "TIME",
        metrics: complianceMetrics(),
      },
    ],
    ...overrides,
  };
}

export function salesTrend(overrides: Partial<import("../types/api").SalesTrendResponse> = {}) {
  return {
    branchId: "1",
    branchName: "Sucursal Centro",
    currentMonth: { period: "2026-08", totalSales: 150, salesCount: 2 },
    previousMonths: [
      { period: "2026-05", totalSales: 0, salesCount: 0 },
      { period: "2026-06", totalSales: 0, salesCount: 0 },
      { period: "2026-07", totalSales: 80, salesCount: 1 },
    ],
    growthVsPreviousMonthPercentage: 87.5,
    ...overrides,
  };
}

export function inventoryDemand(overrides: Partial<import("../types/api").InventoryDemandResponse> = {}) {
  return {
    branchId: "1",
    branchName: "Sucursal Centro",
    windowFrom: "2026-05-01T00:00:00Z",
    windowTo: "2026-09-01T00:00:00Z",
    topDemand: [
      { productId: "10", sku: "SKU-001", name: "Cemento gris", unitsSold: 20, currentStock: 10, turnoverRatio: 2 },
    ],
    lowDemand: [
      { productId: "11", sku: "SKU-002", name: "Arena fina", unitsSold: 0, currentStock: 5, turnoverRatio: 0 },
    ],
    ...overrides,
  };
}

export function activeTransfersDashboard(overrides: Partial<import("../types/api").ActiveTransfersDashboardResponse> = {}) {
  return {
    branchId: "1",
    branchName: "Sucursal Centro",
    activeCount: 1,
    totalUnitsInTransit: 4,
    totalUnitsPendingDispatch: 0,
    transfers: [
      {
        transferId: "500",
        transferNumber: "TR-ABC12345",
        status: "IN_TRANSIT" as const,
        originBranchId: "1",
        destinationBranchId: "2",
        urgency: false,
        unitsInTransit: 4,
        unitsPendingDispatch: 0,
      },
    ],
    ...overrides,
  };
}

export function replenishmentDashboard(overrides: Partial<import("../types/api").ReplenishmentDashboardResponse> = {}) {
  return {
    branchId: "1",
    branchName: "Sucursal Centro",
    lowStockCount: 1,
    mostUrgent: [{ productId: "10", sku: "SKU-001", name: "Cemento gris", quantityOnHand: 2, minimumStock: 10 }],
    ...overrides,
  };
}

export function stockAlert(overrides: Partial<StockAlert> = {}): StockAlert {
  return {
    id: "900",
    branchId: "1",
    productId: "10",
    sku: "SKU-001",
    name: "Cemento gris",
    quantityOnHand: 5,
    minimumStock: 10,
    status: "ACTIVE",
    triggeredAt: "2026-08-27T10:00:00Z",
    resolvedAt: null,
    ...overrides,
  };
}

export function branchComparison(overrides: Partial<import("../types/api").BranchComparisonResponse> = {}) {
  return {
    branches: [
      { branchId: "1", branchName: "Sucursal Centro", currentMonthSales: 150, activeTransfersCount: 1, lowStockCount: 1 },
      { branchId: "2", branchName: "Sucursal Norte", currentMonthSales: 0, activeTransfersCount: 0, lowStockCount: 0 },
    ],
    ...overrides,
  };
}

export function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalPages: number }> = {}) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    ...overrides,
  };
}

/**
 * Responde a las consultas de catálogo que cualquier pantalla necesita
 * (sucursales, unidades, índice de productos). Devuelve `undefined` para que
 * cada prueba maneje lo que le interesa.
 */
export function catalogResponse(url: string): Response | undefined {
  if (url.includes("/branches")) return jsonResponse(200, page(BRANCHES));
  if (url.includes("/roles")) return jsonResponse(200, ROLES);
  if (url.includes("/units-of-measure")) return jsonResponse(200, UNITS);
  if (/\/products\/\d+\/units/.test(url)) return jsonResponse(200, PRODUCT_UNITS);
  if (url.includes("/suppliers")) return jsonResponse(200, page(SUPPLIERS));
  if (/\/price-lists\/\d+\/prices/.test(url)) return jsonResponse(200, PRICES);
  if (url.includes("/price-lists")) return jsonResponse(200, page(PRICE_LISTS));
  if (url.includes("/routes")) return jsonResponse(200, page(ROUTES));
  return undefined;
}

/**
 * Respuestas para las cuatro consultas del Dashboard, con datos válidos por
 * defecto. La raíz ("/") redirige al Dashboard para cualquier rol, así que
 * cualquier prueba que renderice "/" (aunque no sea sobre el Dashboard en sí)
 * necesita esto — no solo `dashboard.test.tsx` — o el panel de tendencia de
 * ventas revienta al recibir una forma de respuesta genérica en vez de la suya.
 */
export function dashboardRoutes(overrides: (url: string) => Response | undefined = () => undefined) {
  return (url: string) => {
    const custom = overrides(url);
    if (custom) return custom;
    const catalog = catalogResponse(url);
    if (catalog) return catalog;
    if (url.includes("/dashboard/sales-summary")) return jsonResponse(200, salesTrend());
    if (url.includes("/dashboard/inventory-rotation")) return jsonResponse(200, inventoryDemand());
    if (url.includes("/dashboard/active-transfers")) return jsonResponse(200, activeTransfersDashboard());
    if (url.includes("/dashboard/replenishment")) return jsonResponse(200, replenishmentDashboard());
    if (url.includes("/dashboard/branch-comparison")) return jsonResponse(200, branchComparison());
    return jsonResponse(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  };
}
