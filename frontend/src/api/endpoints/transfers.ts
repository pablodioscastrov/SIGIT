import { apiRequest } from "../httpClient";
import type {
  ApplyDiscrepancyTreatmentRequest,
  ApproveTransferRequest,
  CreateTransferRequest,
  DiscrepancyTreatmentResponse,
  DispatchTransferRequest,
  Page,
  ReceiveTransferRequest,
  Transfer,
  TransferStatus,
} from "../../types/api";

/** docs/API_DESIGN.md, sección 7.9. */

export function listTransfers(params: {
  branchId?: string;
  role?: "origin" | "destination";
  status?: TransferStatus;
  page?: number;
  size?: number;
}): Promise<Page<Transfer>> {
  return apiRequest<Page<Transfer>>("/transfers", { query: params });
}

export function getTransfer(id: string): Promise<Transfer> {
  return apiRequest<Transfer>(`/transfers/${id}`);
}

export function requestTransfer(body: CreateTransferRequest, idempotencyKey: string): Promise<Transfer> {
  return apiRequest<Transfer>("/transfers", { method: "POST", body, idempotencyKey });
}

export function approveTransfer(id: string, body: ApproveTransferRequest): Promise<Transfer> {
  return apiRequest<Transfer>(`/transfers/${id}/approve`, { method: "POST", body });
}

export function rejectTransfer(id: string): Promise<Transfer> {
  return apiRequest<Transfer>(`/transfers/${id}/reject`, { method: "POST" });
}

export function dispatchTransfer(id: string, body: DispatchTransferRequest): Promise<Transfer> {
  return apiRequest<Transfer>(`/transfers/${id}/dispatch`, { method: "POST", body });
}

export function receiveTransfer(id: string, body: ReceiveTransferRequest): Promise<Transfer> {
  return apiRequest<Transfer>(`/transfers/${id}/receive`, { method: "POST", body });
}

export function applyDiscrepancyTreatment(
  transferId: string,
  itemId: string,
  body: ApplyDiscrepancyTreatmentRequest,
): Promise<DiscrepancyTreatmentResponse> {
  return apiRequest<DiscrepancyTreatmentResponse>(`/transfers/${transferId}/items/${itemId}/discrepancy-treatment`, {
    method: "POST",
    body,
  });
}
