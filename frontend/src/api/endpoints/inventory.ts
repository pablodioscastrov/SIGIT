import { apiRequest } from "../httpClient";
import type { InventoryAdjustmentRequest, InventoryMovement, InventoryRow, Page } from "../../types/api";

/** docs/API_DESIGN.md, sección 7.5. */

export function listInventory(params: {
  branchId?: string;
  productId?: string;
  search?: string;
  lowStock?: boolean;
  page?: number;
  size?: number;
}): Promise<Page<InventoryRow>> {
  return apiRequest<Page<InventoryRow>>("/inventory", { query: params });
}

export function listMovements(params: {
  branchId?: string;
  productId?: string;
  reason?: string;
  page?: number;
  size?: number;
}): Promise<Page<InventoryMovement>> {
  return apiRequest<Page<InventoryMovement>>("/inventory-movements", { query: params });
}

/**
 * Ajuste manual (flujo G, BR-023). El encabezado `Idempotency-Key` no se
 * envía porque este endpoint todavía no lo exige — ver la limitación
 * registrada en docs/STATUS.md; cuando se implemente, se añade aquí.
 */
export function createAdjustment(body: InventoryAdjustmentRequest): Promise<InventoryMovement> {
  return apiRequest<InventoryMovement>("/inventory/adjustments", { method: "POST", body });
}
