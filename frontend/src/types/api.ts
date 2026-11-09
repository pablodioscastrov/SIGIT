/**
 * Tipos del contrato REST v1.
 *
 * Están escritos a mano y no generados desde `docs/openapi.yaml` a propósito:
 * esa especificación es explícitamente parcial ("inicial y representativa, no
 * exhaustiva", sección 10 del propio documento), así que un cliente generado
 * quedaría incompleto justo en los recursos que no figuran en el YAML, con la
 * falsa apariencia de estar completo. Cuando la especificación cubra el 100%
 * del contrato, generarlos pasa a ser la mejor opción.
 *
 * Convenciones que se respetan aquí (docs/API_DESIGN.md, sección 1):
 * los identificadores viajan como `string`, y las fechas como ISO-8601 UTC.
 */

export type Role = "ADMIN" | "MANAGER" | "OPERATOR";

/** Sobre uniforme de error (docs/API_DESIGN.md, sección 3). */
export interface ApiErrorBody {
  error: {
    code: string;
    message: string;
    status: number;
    requestId: string;
    details?: Array<{ field: string; issue: string }>;
  };
}

/** Sobre uniforme de paginación (docs/API_DESIGN.md, sección 1). */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UserSummary {
  id: string;
  name: string;
  email: string;
  role: Role;
  /** `null` para ADMIN: alcance global, sin sucursal fija. */
  branchId: string | null;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  expiresIn: number;
  user: UserSummary;
}

export interface Branch {
  id: string;
  code: string;
  name: string;
  location: string | null;
  active: boolean;
}

export interface CreateBranchRequest {
  code: string;
  name: string;
  location?: string | null;
}

/** `code` no se edita (clave de negocio inmutable, docs/API_DESIGN.md sección 7.3). */
export interface UpdateBranchRequest {
  name: string;
  location?: string | null;
}

// ---- Usuarios y roles (docs/API_DESIGN.md, sección 7.2) ----

/** Forma de gestión administrativa (UC-14) — distinta de `UserSummary`, el perfil de la sesión actual. */
export interface User {
  id: string;
  name: string;
  email: string;
  role: Role;
  /** `null` para ADMIN: alcance global, sin sucursal fija. */
  branchId: string | null;
  active: boolean;
  /** Motivo de la desactivación vigente; `null` si está activo o nunca fue desactivado. */
  deactivationReason: string | null;
}

export interface CreateUserRequest {
  name: string;
  email: string;
  password: string;
  role: Role;
  /** Obligatorio salvo para ADMIN, que no debe tener sucursal (backend: `ADMIN_SIN_SUCURSAL`/`SUCURSAL_REQUERIDA`). */
  branchId?: number | null;
}

/** BR-058: edición de nombre/correo/rol/sucursal — sin contraseña, eso sigue siendo un flujo aparte. */
export interface UpdateUserRequest {
  name: string;
  email: string;
  role: Role;
  branchId?: number | null;
}

export interface DeactivateUserRequest {
  reason: string;
}

export interface RoleInfo {
  code: Role;
  name: string;
}

// ---- Productos y unidades de medida (docs/API_DESIGN.md, sección 7.4) ----

export interface Product {
  id: string;
  sku: string;
  name: string;
  description: string | null;
  baseUnitOfMeasureId: string;
  active: boolean;
  /** Valor por defecto para `Inventory.minimumStock` la primera vez que una sucursal registra movimiento de este producto (BR-010). */
  minimumStock: number;
  /** Precio vigente en la lista de precios global por defecto (BR-051); `null` si no tiene ninguno configurado. */
  salePrice: number | null;
}

export interface CreateProductRequest {
  sku: string;
  name: string;
  description?: string | null;
  baseUnitOfMeasureId: number;
  minimumStock: number;
  unitPrice: number;
}

export interface UpdateProductRequest {
  name: string;
  description?: string | null;
  /** BR-057: editable después de creado, a diferencia del SKU y la unidad base. */
  unitPrice: number;
  /** BR-059: editable después de creado, a diferencia del SKU y la unidad base. */
  minimumStock: number;
}

export interface UnitOfMeasure {
  id: string;
  code: string;
  name: string;
}

/** Unidad alternativa de un producto con su factor hacia la unidad base (BR-011). */
export interface ProductUnit {
  unitOfMeasureId: string;
  unitCode: string;
  unitName: string;
  /** Llega como número JSON; se maneja como string en formularios para no perder decimales. */
  conversionFactorToBase: number;
  baseUnit: boolean;
}

// ---- Inventario (docs/API_DESIGN.md, sección 7.5) ----

export interface InventoryRow {
  id: string;
  productId: string;
  branchId: string;
  quantityOnHand: number;
  averageUnitCost: number;
  minimumStock: number;
  updatedAt: string;
}

export type MovementDirection = "INGRESO" | "RETIRO";

export type MovementReason =
  | "COMPRA"
  | "DEVOLUCION"
  | "AJUSTE_INGRESO"
  | "VENTA"
  | "MERMA"
  | "AJUSTE_RETIRO"
  | "TRANSFERENCIA_SALIDA"
  | "TRANSFERENCIA_ENTRADA";

export interface InventoryMovement {
  id: string;
  productId: string;
  branchId: string;
  direction: MovementDirection;
  reason: MovementReason;
  quantity: number;
  unitOfMeasureId: string;
  responsibleUserId: string;
  occurredAt: string;
  notes: string | null;
  /** Documento que originó el movimiento; nulo en un ajuste manual (BR-023). */
  source: { type: "PURCHASE_ORDER" | "SALE" | "TRANSFER"; id: string } | null;
}

/**
 * Los motivos que un ajuste manual puede declarar, por dirección (BR-027).
 * El backend rechaza cualquier otra combinación con 422; aquí solo se limita
 * lo que ofrece el desplegable para no proponer algo que será rechazado.
 */
export const ADJUSTMENT_REASONS: Record<MovementDirection, MovementReason[]> = {
  INGRESO: ["AJUSTE_INGRESO", "DEVOLUCION"],
  RETIRO: ["AJUSTE_RETIRO", "MERMA"],
};

export interface InventoryAdjustmentRequest {
  branchId: number;
  productId: number;
  unitOfMeasureId?: number | null;
  direction: MovementDirection;
  reason?: MovementReason | null;
  quantity: number;
  notes: string;
}

/** Alertas de stock mínimo (BR-010, UC-16, RF-010/RF-036 — funcionalidad adicional). */
export type StockAlertStatus = "ACTIVE" | "RESOLVED";

export interface StockAlert {
  id: string;
  branchId: string;
  productId: string;
  sku: string;
  name: string;
  quantityOnHand: number;
  minimumStock: number;
  status: StockAlertStatus;
  triggeredAt: string;
  resolvedAt: string | null;
}

// ---- Proveedores (docs/API_DESIGN.md, sección 7.6) ----

export interface Supplier {
  id: string;
  name: string;
  taxId: string;
  contactName: string | null;
  phone: string | null;
  email: string | null;
  active: boolean;
}

export interface CreateSupplierRequest {
  name: string;
  taxId: string;
  contactName?: string | null;
  phone?: string | null;
  email?: string | null;
}

export interface UpdateSupplierRequest {
  name: string;
  contactName?: string | null;
  phone?: string | null;
  email?: string | null;
}

// ---- Compras (docs/API_DESIGN.md, sección 7.7) ----

export type PurchaseOrderStatus = "CREATED" | "PARTIALLY_RECEIVED" | "RECEIVED" | "CANCELLED";

export interface PurchaseOrderItem {
  id: string;
  productId: string;
  unitOfMeasureId: string;
  quantityOrdered: number;
  quantityReceived: number;
  pending: number;
  unitPrice: number;
  discountPercentage: number;
  lineTotal: number;
}

export interface PurchaseOrder {
  id: string;
  orderNumber: string;
  supplierId: string;
  branchId: string;
  status: PurchaseOrderStatus;
  orderDate: string;
  paymentTerm: string | null;
  items: PurchaseOrderItem[];
}

export interface CreatePurchaseOrderItemRequest {
  productId: number;
  unitOfMeasureId?: number | null;
  quantityOrdered: number;
  unitPrice: number;
  discountPercentage?: number | null;
}

export interface CreatePurchaseOrderRequest {
  supplierId: number;
  branchId: number;
  paymentTerm?: string | null;
  items: CreatePurchaseOrderItemRequest[];
}

export interface ReceiptItemRequest {
  purchaseOrderItemId: number;
  quantityReceived: number;
  unitPrice: number;
}

export interface PurchaseReceiptRequest {
  items: ReceiptItemRequest[];
}

export interface PurchaseReceiptResponse {
  purchaseOrderId: string;
  status: PurchaseOrderStatus;
  items: Array<{ purchaseOrderItemId: string; quantityOrdered: number; quantityReceived: number; pending: number }>;
  inventoryUpdates: Array<{ productId: string; branchId: string; quantityOnHand: number; averageUnitCost: number }>;
}

// ---- Ventas / listas de precios (docs/API_DESIGN.md, sección 7.8) ----

export type SaleStatus = "CONFIRMED";

export interface SaleItem {
  /** BR-052: referencia necesaria para devolver esta línea. */
  id: string;
  productId: string;
  quantity: number;
  unitOfMeasureId: string;
  unitPrice: number;
  discountPercentage: number;
  lineTotal: number;
  /** Cantidad ya devuelta y cantidad que todavía se puede devolver (BR-052). */
  quantityReturned: number;
  pending: number;
}

export interface Sale {
  id: string;
  saleNumber: string;
  branchId: string;
  soldByUserId: string;
  /** Resuelto por el backend (BR-054): `GET /users` es ADMIN-only. */
  soldByUserName: string | null;
  status: SaleStatus;
  saleDate: string;
  items: SaleItem[];
  subtotal: number;
  discountTotal: number;
  total: number;
}

export interface CreateSaleReturnItemRequest {
  saleItemId: number;
  quantity: number;
}

export interface CreateSaleReturnRequest {
  items: CreateSaleReturnItemRequest[];
}

export interface SaleReturnResponse {
  saleId: string;
  items: { saleItemId: string; quantity: number; quantityReturned: number; pending: number }[];
  inventoryUpdates: { productId: string; branchId: string; quantityOnHand: number }[];
}

export interface CreateSaleItemRequest {
  productId: number;
  unitOfMeasureId?: number | null;
  quantity: number;
  discountPercentage?: number | null;
}

export interface CreateSaleRequest {
  branchId: number;
  priceListId?: number | null;
  items: CreateSaleItemRequest[];
}

export interface PriceList {
  id: string;
  name: string;
  branchId: string | null;
  active: boolean;
}

export interface Price {
  id: string;
  priceListId: string;
  productId: string;
  unitPrice: number;
  validFrom: string;
  validTo: string | null;
}

// ---- Transferencias (docs/API_DESIGN.md, sección 7.9) ----

export type TransferStatus =
  | "REQUESTED"
  | "APPROVED"
  | "REJECTED"
  | "IN_TRANSIT"
  | "RECEIVED_COMPLETE"
  | "RECEIVED_PARTIAL"
  | "CLOSED";

export type DiscrepancyTreatment = "REENVIO" | "AJUSTE" | "RECLAMACION";

export interface TransferItem {
  id: string;
  productId: string;
  unitOfMeasureId: string;
  quantityRequested: number;
  quantityApproved: number | null;
  quantityShipped: number | null;
  quantityReceived: number | null;
  quantityMissing: number | null;
  discrepancyTreatment: DiscrepancyTreatment | null;
  followUpTransferId: string | null;
  /** Detalle del tratamiento (p. ej. el contenido de una reclamación) — visible para origen y destino una vez registrado. */
  treatmentNotes: string | null;
}

export interface Transfer {
  id: string;
  transferNumber: string;
  status: TransferStatus;
  originBranchId: string;
  destinationBranchId: string;
  routeId: string | null;
  urgency: boolean;
  carrierName: string | null;
  estimatedArrivalDate: string | null;
  requestedByUserId: string;
  approvedByUserId: string | null;
  requestedAt: string;
  approvedAt: string | null;
  dispatchedAt: string | null;
  receivedAt: string | null;
  items: TransferItem[];
}

export interface CreateTransferItemRequest {
  productId: number;
  quantityRequested: number;
}

export interface CreateTransferRequest {
  originBranchId: number;
  destinationBranchId: number;
  urgency: boolean;
  items: CreateTransferItemRequest[];
}

export interface ApproveTransferItemRequest {
  transferItemId: number;
  quantityApproved: number;
}

export interface ApproveTransferRequest {
  items: ApproveTransferItemRequest[];
}

export interface DispatchTransferItemRequest {
  transferItemId: number;
  quantityShipped: number;
}

export interface DispatchTransferRequest {
  carrierName?: string | null;
  estimatedArrivalDate?: string | null;
  items: DispatchTransferItemRequest[];
}

export interface ReceiveTransferItemRequest {
  transferItemId: number;
  quantityReceived: number;
}

export interface ReceiveTransferRequest {
  items: ReceiveTransferItemRequest[];
}

export interface ApplyDiscrepancyTreatmentRequest {
  treatment: DiscrepancyTreatment;
  notes?: string | null;
}

export interface DiscrepancyTreatmentResponse {
  transferItemId: string;
  discrepancyTreatment: DiscrepancyTreatment;
  notes: string | null;
  followUpTransferId: string | null;
  transferStatus: TransferStatus;
}

// ---- Logística: rutas y cumplimiento (docs/API_DESIGN.md, sección 7.9/7.10) ----

export type RouteClassification = "PRIORITY" | "COST" | "TIME";

export interface Route {
  id: string;
  originBranchId: string;
  destinationBranchId: string;
  classification: RouteClassification;
}

export interface CreateRouteRequest {
  originBranchId: number;
  destinationBranchId: number;
  classification: RouteClassification;
}

export interface UpdateRouteRequest {
  classification: RouteClassification;
}

export interface ComplianceMetrics {
  dispatched: number;
  delivered: number;
  inTransit: number;
  overdueInTransit: number;
  onTime: number;
  late: number;
  notEvaluable: number;
  withShortages: number;
  complianceRate: number | null;
  averageDeliveryHours: number | null;
}

export interface RouteCompliance {
  routeId: string | null;
  originBranchId: string;
  destinationBranchId: string;
  classification: RouteClassification | null;
  metrics: ComplianceMetrics;
}

export interface LogisticsComplianceResponse {
  appliedFilters: { branchId: string | null; routeId: string | null; dispatchedFrom: string | null; dispatchedTo: string | null };
  summary: ComplianceMetrics;
  byRoute: RouteCompliance[];
}

// ---- Dashboard (docs/API_DESIGN.md, sección 7.10; RF-031 a RF-035) ----

export interface MonthlySales {
  period: string;
  totalSales: number;
  salesCount: number;
}

export interface SalesTrendResponse {
  branchId: string;
  branchName: string;
  currentMonth: MonthlySales;
  /** Cronológico ascendente: el más antiguo primero. */
  previousMonths: MonthlySales[];
  /** `null` cuando el mes anterior no tuvo ventas — no calculable, nunca Infinity. */
  growthVsPreviousMonthPercentage: number | null;
}

export interface ProductDemandEntry {
  productId: string;
  sku: string | null;
  name: string | null;
  unitsSold: number;
  currentStock: number;
  /** `null` cuando el stock actual es 0 — no calculable (ver ADR del dashboard). */
  turnoverRatio: number | null;
}

export interface InventoryDemandResponse {
  branchId: string;
  branchName: string;
  windowFrom: string;
  windowTo: string;
  topDemand: ProductDemandEntry[];
  lowDemand: ProductDemandEntry[];
}

export interface ActiveTransferEntry {
  transferId: string;
  transferNumber: string;
  status: TransferStatus;
  originBranchId: string;
  destinationBranchId: string;
  urgency: boolean;
  unitsInTransit: number;
  unitsPendingDispatch: number;
}

export interface ActiveTransfersDashboardResponse {
  branchId: string;
  branchName: string;
  activeCount: number;
  totalUnitsInTransit: number;
  totalUnitsPendingDispatch: number;
  transfers: ActiveTransferEntry[];
}

export interface ReplenishmentEntry {
  productId: string;
  sku: string | null;
  name: string | null;
  quantityOnHand: number;
  minimumStock: number;
}

export interface ReplenishmentDashboardResponse {
  branchId: string;
  branchName: string;
  lowStockCount: number;
  mostUrgent: ReplenishmentEntry[];
}

export interface BranchMetrics {
  branchId: string;
  branchName: string;
  currentMonthSales: number;
  activeTransfersCount: number;
  lowStockCount: number;
}

export interface BranchComparisonResponse {
  branches: BranchMetrics[];
}
