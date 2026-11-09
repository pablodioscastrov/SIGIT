import type { PurchaseOrderStatus } from "../../types/api";

export const PURCHASE_STATUS_LABELS: Record<PurchaseOrderStatus, string> = {
  CREATED: "Creada",
  PARTIALLY_RECEIVED: "Recibida parcialmente",
  RECEIVED: "Recibida",
  CANCELLED: "Cancelada",
};

export function orderTotal(items: { lineTotal: number }[]): number {
  return items.reduce((sum, item) => sum + item.lineTotal, 0);
}
