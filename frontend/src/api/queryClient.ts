import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "./ApiError";

/**
 * Configuración de caché y reintentos.
 *
 * <p>`retry`: reintentar un 4xx no tiene sentido — un 403 o un 422 van a
 * fallar igual la segunda vez, y reintentar una operación de negocio podría
 * duplicar efectos. Solo se reintenta lo que puede ser transitorio: fallos de
 * red y errores del servidor.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
            return false;
          }
          return failureCount < 2;
        },
      },
      mutations: {
        // Una mutación fallida nunca se reintenta sola: puede tener efectos de
        // negocio. El reintento lo decide el usuario, y las operaciones de
        // creación repetible se protegen con Idempotency-Key.
        retry: false,
      },
    },
  });
}

/**
 * Claves de caché centralizadas: evita que dos pantallas usen strings
 * distintos para el mismo recurso y que una invalidación no alcance a la otra.
 * Es también el punto donde se engancharán las señales SSE (ADR-009): al
 * recibir `inventory.updated` se invalida la clave correspondiente y TanStack
 * Query vuelve a consultar REST, que sigue siendo la fuente de verdad.
 */
export const queryKeys = {
  branches: (params?: unknown) => ["branches", params ?? {}] as const,
  users: (params?: unknown) => ["users", params ?? {}] as const,
  roles: () => ["roles"] as const,
  products: (params?: unknown) => ["products", params ?? {}] as const,
  productUnits: (productId: string) => ["product-units", productId] as const,
  unitsOfMeasure: () => ["units-of-measure"] as const,
  inventoryMovements: (params?: unknown) => ["inventory-movements", params ?? {}] as const,
  stockAlerts: (params?: unknown) => ["stock-alerts", params ?? {}] as const,
  suppliers: (params?: unknown) => ["suppliers", params ?? {}] as const,
  purchaseOrders: (params?: unknown) => ["purchase-orders", params ?? {}] as const,
  purchaseOrder: (id: string) => ["purchase-orders", id] as const,
  sales: (params?: unknown) => ["sales", params ?? {}] as const,
  sale: (id: string) => ["sales", id] as const,
  priceLists: (params?: unknown) => ["price-lists", params ?? {}] as const,
  prices: (priceListId: string) => ["price-lists", priceListId, "prices"] as const,
  inventory: (params?: unknown) => ["inventory", params ?? {}] as const,
  transfers: (params?: unknown) => ["transfers", params ?? {}] as const,
  transfer: (id: string) => ["transfers", id] as const,
  routes: (params?: unknown) => ["routes", params ?? {}] as const,
  logisticsCompliance: (params?: unknown) => ["logistics-compliance", params ?? {}] as const,
  dashboardSales: (params?: unknown) => ["dashboard-sales", params ?? {}] as const,
  dashboardDemand: (params?: unknown) => ["dashboard-demand", params ?? {}] as const,
  // Bajo el mismo prefijo que la lista de transferencias a propósito: cuando
  // llega `transfer.status-changed` por SSE, la invalidación de
  // `queryPrefixes.transfers` alcanza también a este panel del dashboard sin
  // un mecanismo aparte (ver useTransferRealtime).
  dashboardActiveTransfers: (branchId: string) => ["transfers", "dashboard-active", branchId] as const,
  dashboardReplenishment: (params?: unknown) => ["dashboard-replenishment", params ?? {}] as const,
  dashboardBranchComparison: () => ["dashboard-branch-comparison"] as const,
};

/**
 * Prefijos para invalidar todas las variantes de un recurso tras una mutación
 * —cualquier página y cualquier combinación de filtros—, sin escribir el
 * string suelto en cada pantalla.
 */
export const queryPrefixes = {
  branches: ["branches"] as const,
  users: ["users"] as const,
  suppliers: ["suppliers"] as const,
  products: ["products"] as const,
  inventory: ["inventory"] as const,
  inventoryMovements: ["inventory-movements"] as const,
  stockAlerts: ["stock-alerts"] as const,
  purchaseOrders: ["purchase-orders"] as const,
  sales: ["sales"] as const,
  transfers: ["transfers"] as const,
  routes: ["routes"] as const,
};
