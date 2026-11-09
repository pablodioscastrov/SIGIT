import { apiRequest } from "../httpClient";
import type { LoginRequest, LoginResponse, UserSummary } from "../../types/api";

/** docs/API_DESIGN.md, sección 7.1. */
export function login(credentials: LoginRequest): Promise<LoginResponse> {
  return apiRequest<LoginResponse>("/auth/login", { method: "POST", body: credentials });
}

export function fetchCurrentUser(): Promise<UserSummary> {
  return apiRequest<UserSummary>("/auth/me");
}
