import { apiRequest } from "../httpClient";
import type { Branch, CreateBranchRequest, Page, UpdateBranchRequest } from "../../types/api";

/**
 * docs/API_DESIGN.md, sección 7.3. Este módulo existe como referencia del
 * patrón que seguirán el resto de recursos: una función por endpoint, tipada
 * con el contrato, sin lógica de presentación ni de negocio.
 */
export function listBranches(params: { active?: boolean; page?: number; size?: number } = {}): Promise<Page<Branch>> {
  return apiRequest<Page<Branch>>("/branches", { query: params });
}

/** Alta de sucursal: ADMIN únicamente (UC-15). */
export function createBranch(body: CreateBranchRequest): Promise<Branch> {
  return apiRequest<Branch>("/branches", { method: "POST", body });
}

export function updateBranch(id: string, body: UpdateBranchRequest): Promise<Branch> {
  return apiRequest<Branch>(`/branches/${id}`, { method: "PATCH", body });
}

export function setBranchActive(id: string, active: boolean): Promise<Branch> {
  return apiRequest<Branch>(`/branches/${id}/${active ? "activate" : "deactivate"}`, { method: "POST" });
}

/** Eliminación real: el backend la rechaza (409) si la sucursal tiene datos asociados. */
export function deleteBranch(id: string): Promise<void> {
  return apiRequest<void>(`/branches/${id}`, { method: "DELETE" });
}
