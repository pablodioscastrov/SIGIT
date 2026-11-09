import { apiRequest } from "../httpClient";
import type { CreateSupplierRequest, Page, Supplier, UpdateSupplierRequest } from "../../types/api";

/** docs/API_DESIGN.md, sección 7.6. CRUD completo abierto a cualquier rol autenticado (BR-049). */

export function listSuppliers(params: { search?: string; active?: boolean; page?: number; size?: number } = {}): Promise<Page<Supplier>> {
  return apiRequest<Page<Supplier>>("/suppliers", { query: params });
}

export function createSupplier(body: CreateSupplierRequest): Promise<Supplier> {
  return apiRequest<Supplier>("/suppliers", { method: "POST", body });
}

export function updateSupplier(id: string, body: UpdateSupplierRequest): Promise<Supplier> {
  return apiRequest<Supplier>(`/suppliers/${id}`, { method: "PATCH", body });
}

export function setSupplierActive(id: string, active: boolean): Promise<Supplier> {
  return apiRequest<Supplier>(`/suppliers/${id}/${active ? "activate" : "deactivate"}`, { method: "POST" });
}

/** Eliminación real: el backend la rechaza (409) si el proveedor tiene órdenes de compra asociadas. */
export function deleteSupplier(id: string): Promise<void> {
  return apiRequest<void>(`/suppliers/${id}`, { method: "DELETE" });
}
