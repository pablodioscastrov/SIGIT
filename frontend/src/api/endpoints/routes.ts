import { apiRequest } from "../httpClient";
import type { CreateRouteRequest, Page, Route, RouteClassification, UpdateRouteRequest } from "../../types/api";

/** docs/API_DESIGN.md, sección 7.9 (routes). */

export function listRoutes(params: { branchId?: string; classification?: RouteClassification; page?: number; size?: number } = {}): Promise<Page<Route>> {
  return apiRequest<Page<Route>>("/routes", { query: params });
}

export function createRoute(body: CreateRouteRequest): Promise<Route> {
  return apiRequest<Route>("/routes", { method: "POST", body });
}

export function reclassifyRoute(id: string, body: UpdateRouteRequest): Promise<Route> {
  return apiRequest<Route>(`/routes/${id}`, { method: "PATCH", body });
}
