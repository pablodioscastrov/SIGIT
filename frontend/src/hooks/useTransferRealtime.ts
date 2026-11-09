import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { env } from "../config/env";
import { getStoredToken } from "../auth/session";
import { queryKeys, queryPrefixes } from "../api/queryClient";
import type { Role } from "../types/api";

/**
 * Suscripción SSE a las señales de transferencia (`transfer.status-changed`,
 * `transfer.discrepancy-opened`; ADR-007/ADR-009 — ya implementado y
 * aprobado, aquí solo se consume). El evento es una señal, nunca el dato: al
 * recibirlo se invalida la caché y TanStack Query vuelve a consultar REST,
 * que sigue siendo la fuente de verdad.
 *
 * <p>`EventSource` no admite encabezados propios, así que el token viaja por
 * query string — excepción ya documentada y aceptada solo para esta ruta
 * (`docs/API_DESIGN.md`, sección 2; `JwtAuthenticationFilter`).
 *
 * <p>Se monta únicamente en las pantallas de transferencias: es la única
 * vista de esta fase que lo necesita (RF-029), en línea con "solo las vistas
 * que lo necesiten realmente" de ADR-009.
 */
export function useTransferRealtime(user: { role: Role; branchId: string | null } | null) {
  const queryClient = useQueryClient();
  const role = user?.role;
  const branchId = user?.branchId;

  useEffect(() => {
    if (!role) return;
    const token = getStoredToken();
    if (!token) return;

    const params = new URLSearchParams({ access_token: token });
    if (role !== "ADMIN" && branchId) {
      params.append("branchId", branchId);
    }
    const source = new EventSource(`${env.apiBaseUrl}/events?${params.toString()}`);

    const invalidateList = () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.transfers });
    };

    const handleTransferEvent = (event: MessageEvent) => {
      invalidateList();
      try {
        const payload = JSON.parse(event.data as string) as { resourceId: string };
        void queryClient.invalidateQueries({ queryKey: queryKeys.transfer(payload.resourceId) });
      } catch {
        // Sin `resourceId` legible, la invalidación del listado ya alcanza
        // para que la pantalla se ponga al día en el próximo refetch.
      }
    };

    source.addEventListener("transfer.status-changed", handleTransferEvent);
    source.addEventListener("transfer.discrepancy-opened", handleTransferEvent);
    // Al reconectar tras una caída se reconcilia contra REST: el canal no
    // reenvía lo perdido durante la desconexión (ADR-009).
    source.onopen = invalidateList;

    return () => source.close();
  }, [role, branchId, queryClient]);
}
