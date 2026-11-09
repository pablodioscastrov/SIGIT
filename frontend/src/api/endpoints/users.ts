import { apiRequest } from "../httpClient";
import type { CreateUserRequest, Page, RoleInfo, UpdateUserRequest, User } from "../../types/api";

/** docs/API_DESIGN.md, sección 7.2. Módulo de lectura y escritura exclusivo de ADMIN (UC-14). */

export function listUsers(
  params: { branchId?: string; role?: string; active?: boolean; page?: number; size?: number } = {},
): Promise<Page<User>> {
  return apiRequest<Page<User>>("/users", { query: params });
}

export function createUser(body: CreateUserRequest): Promise<User> {
  return apiRequest<User>("/users", { method: "POST", body });
}

/** BR-058: edita nombre/correo/rol/sucursal — la contraseña sigue siendo un flujo aparte. */
export function updateUser(id: string, body: UpdateUserRequest): Promise<User> {
  return apiRequest<User>(`/users/${id}`, { method: "PATCH", body });
}

export function activateUser(id: string): Promise<User> {
  return apiRequest<User>(`/users/${id}/activate`, { method: "POST" });
}

/** Desactivar exige un motivo (UC-14): queda visible mientras el usuario siga desactivado. */
export function deactivateUser(id: string, reason: string): Promise<User> {
  return apiRequest<User>(`/users/${id}/deactivate`, { method: "POST", body: { reason } });
}

/** Eliminación real: el backend la rechaza (409) si el usuario tiene historial asociado. */
export function deleteUser(id: string): Promise<void> {
  return apiRequest<void>(`/users/${id}`, { method: "DELETE" });
}

/** Catálogo fijo de roles para el selector del formulario de alta. */
export function listRoles(): Promise<RoleInfo[]> {
  return apiRequest<RoleInfo[]>("/roles");
}
