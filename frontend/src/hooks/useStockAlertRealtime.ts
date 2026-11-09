import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { env } from "../config/env";
import { getStoredToken } from "../auth/session";
import { queryPrefixes } from "../api/queryClient";
import type { Role } from "../types/api";

/**
 * Suscripción SSE a las señales de alerta de stock (`stock-alert.triggered`,
 * `stock-alert.resolved`; BR-010, UC-16). Mismo patrón que
 * {@code useTransferRealtime}: el evento es solo una señal, nunca el dato —
 * al recibirlo se invalida la caché y TanStack Query vuelve a consultar REST.
 *
 * <p>Hook separado en vez de extender {@code useTransferRealtime} porque esa
 * suscripción está deliberadamente acotada a señales de transferencia (su
 * propio nombre y documentación lo dicen); mezclar aquí un tipo de evento no
 * relacionado la volvería confusa. La contrapartida —dos conexiones SSE por
 * pestaña si ambas pantallas están montadas a la vez— es exactamente el
 * escenario que ADR-009 ya señala como el momento de reconsiderar una
 * suscripción a nivel de layout; se documenta aquí en vez de adelantar ese
 * rediseño sin que se haya pedido.
 */
export function useStockAlertRealtime(user: { role: Role; branchId: string | null } | null) {
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
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.stockAlerts });
    };

    source.addEventListener("stock-alert.triggered", invalidateList);
    source.addEventListener("stock-alert.resolved", invalidateList);
    // Al reconectar tras una caída se reconcilia contra REST: el canal no
    // reenvía lo perdido durante la desconexión (ADR-009).
    source.onopen = invalidateList;

    return () => source.close();
  }, [role, branchId, queryClient]);
}
