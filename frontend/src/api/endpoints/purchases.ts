import { apiRequest } from "../httpClient";
import type {
  CreatePurchaseOrderRequest,
  Page,
  PurchaseOrder,
  PurchaseOrderStatus,
  PurchaseReceiptRequest,
  PurchaseReceiptResponse,
} from "../../types/api";

/** docs/API_DESIGN.md, sección 7.7. */

export function listPurchaseOrders(params: {
  branchId?: string;
  supplierId?: string;
  status?: PurchaseOrderStatus;
  page?: number;
  size?: number;
}): Promise<Page<PurchaseOrder>> {
  return apiRequest<Page<PurchaseOrder>>("/purchase-orders", { query: params });
}

export function getPurchaseOrder(id: string): Promise<PurchaseOrder> {
  return apiRequest<PurchaseOrder>(`/purchase-orders/${id}`);
}

export function createPurchaseOrder(body: CreatePurchaseOrderRequest, idempotencyKey: string): Promise<PurchaseOrder> {
  return apiRequest<PurchaseOrder>("/purchase-orders", { method: "POST", body, idempotencyKey });
}

export function cancelPurchaseOrder(id: string): Promise<PurchaseOrder> {
  return apiRequest<PurchaseOrder>(`/purchase-orders/${id}/cancel`, { method: "POST" });
}

export function receivePurchaseOrder(
  id: string,
  body: PurchaseReceiptRequest,
  idempotencyKey: string,
): Promise<PurchaseReceiptResponse> {
  return apiRequest<PurchaseReceiptResponse>(`/purchase-orders/${id}/receipts`, { method: "POST", body, idempotencyKey });
}
