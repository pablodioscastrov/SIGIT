import { apiRequest } from "../httpClient";
import type {
  ActiveTransfersDashboardResponse,
  BranchComparisonResponse,
  InventoryDemandResponse,
  ReplenishmentDashboardResponse,
  SalesTrendResponse,
} from "../../types/api";

/** docs/API_DESIGN.md, sección 7.10 (RF-031 a RF-035). */

export function getSalesSummary(params: { branchId: string; months?: number }): Promise<SalesTrendResponse> {
  return apiRequest<SalesTrendResponse>("/dashboard/sales-summary", { query: params });
}

export function getInventoryRotation(params: { branchId: string; months?: number; limit?: number }): Promise<InventoryDemandResponse> {
  return apiRequest<InventoryDemandResponse>("/dashboard/inventory-rotation", { query: params });
}

export function getActiveTransfersDashboard(params: { branchId: string }): Promise<ActiveTransfersDashboardResponse> {
  return apiRequest<ActiveTransfersDashboardResponse>("/dashboard/active-transfers", { query: params });
}

export function getReplenishment(params: { branchId: string; limit?: number }): Promise<ReplenishmentDashboardResponse> {
  return apiRequest<ReplenishmentDashboardResponse>("/dashboard/replenishment", { query: params });
}

export function getBranchComparison(): Promise<BranchComparisonResponse> {
  return apiRequest<BranchComparisonResponse>("/dashboard/branch-comparison");
}
