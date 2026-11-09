import { apiDownload, apiRequest } from "../httpClient";
import type { DownloadResult } from "../httpClient";
import type { LogisticsComplianceResponse } from "../../types/api";

/** docs/API_DESIGN.md, sección 7.10. */

export function getLogisticsCompliance(params: {
  branchId?: string;
  routeId?: string;
  dateFrom?: string;
  dateTo?: string;
}): Promise<LogisticsComplianceResponse> {
  return apiRequest<LogisticsComplianceResponse>("/reports/logistics-compliance", { query: params });
}

/**
 * Los cuatro reportes exportables en Excel (BR-056). A diferencia de los
 * listados paginados de cada pantalla, `dateFrom`/`dateTo` son obligatorios
 * aquí — el backend rechaza con 422 `RANGO_FECHAS_REQUERIDO` si falta
 * cualquiera de los dos.
 */
export function exportInventoryMovements(params: {
  branchId?: string;
  productId?: string;
  reason?: string;
  dateFrom: string;
  dateTo: string;
}): Promise<DownloadResult> {
  return apiDownload("/reports/inventory-movements/export", { query: params });
}

export function exportSales(params: {
  branchId?: string;
  status?: string;
  dateFrom: string;
  dateTo: string;
}): Promise<DownloadResult> {
  return apiDownload("/reports/sales/export", { query: params });
}

export function exportTransfers(params: {
  branchId?: string;
  status?: string;
  dateFrom: string;
  dateTo: string;
}): Promise<DownloadResult> {
  return apiDownload("/reports/transfers/export", { query: params });
}

export function exportLogisticsCompliance(params: {
  branchId?: string;
  routeId?: string;
  dateFrom: string;
  dateTo: string;
}): Promise<DownloadResult> {
  return apiDownload("/reports/logistics-compliance/export", { query: params });
}
