import { apiRequest } from "../httpClient";
import type { Page, StockAlert, StockAlertStatus } from "../../types/api";

/** docs/API_DESIGN.md, sección 6: lectura abierta a cualquier rol, cualquier sucursal (RF-003) — igual que `inventory`. Sin escritura: se generan y resuelven automáticamente. */
export function listStockAlerts(params: {
  branchId?: string;
  status?: StockAlertStatus;
  page?: number;
  size?: number;
}): Promise<Page<StockAlert>> {
  return apiRequest<Page<StockAlert>>("/stock-alerts", { query: params });
}
