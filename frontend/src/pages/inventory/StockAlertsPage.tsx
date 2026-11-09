import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { listStockAlerts } from "../../api/endpoints/stockAlerts";
import { queryKeys } from "../../api/queryClient";
import { useAuth } from "../../auth/useAuth";
import { AsyncBoundary } from "../../components/state/states";
import { Pagination } from "../../components/ui/Pagination";
import { useStockAlertRealtime } from "../../hooks/useStockAlertRealtime";
import type { StockAlertStatus } from "../../types/api";

const PAGE_SIZE = 10;

type StatusFilter = StockAlertStatus | "TODAS";

/**
 * Centro de alertas de stock mínimo (BR-010, UC-16, RF-010/RF-036 —
 * funcionalidad adicional elegida). Lectura abierta a cualquier rol y
 * cualquier sucursal, igual que Inventario (RF-003): la alerta se deriva
 * directamente de un dato que ya es público dentro de la organización. Sin
 * acciones de escritura — se generan y resuelven automáticamente al cruzar
 * el umbral (ver docs/adr/ADR-015-alertas-de-stock-minimo.md).
 *
 * <p>`branchId` llega por la URL (no solo del rol) para que "ver las alertas
 * de esta sucursal" sea un enlace desde el panel de reabastecimiento del
 * dashboard — mismo criterio que `MovementsPage`.
 */
export function StockAlertsPage() {
  const { user } = useAuth();
  useStockAlertRealtime(user);

  const [searchParams, setSearchParams] = useSearchParams();
  const branchId = searchParams.get("branchId") ?? user?.branchId ?? "";
  const [status, setStatus] = useState<StatusFilter>("ACTIVE");
  const [page, setPage] = useState(0);

  const branchesQuery = useQuery({
    queryKey: queryKeys.branches({ active: true }),
    queryFn: () => listBranches({ active: true }),
  });
  const branchesById = new Map((branchesQuery.data?.content ?? []).map((branch) => [branch.id, branch]));
  const branchLabel = (id: string) => branchesById.get(id)?.name ?? `Sucursal ${id}`;

  const params = {
    branchId: branchId || undefined,
    status: status === "TODAS" ? undefined : status,
    page,
    size: PAGE_SIZE,
  };
  const query = useQuery({
    queryKey: queryKeys.stockAlerts(params),
    queryFn: () => listStockAlerts(params),
  });

  function changeFilter(apply: () => void) {
    apply();
    setPage(0);
  }

  return (
    <section>
      <div className="page__header">
        <h1>Alertas de stock</h1>
      </div>

      <form className="filters" role="search" onSubmit={(event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="alerts-branch">Sucursal</label>
          <select
            id="alerts-branch"
            value={branchId}
            onChange={(event) =>
              changeFilter(() => {
                const next = new URLSearchParams(searchParams);
                if (event.target.value) next.set("branchId", event.target.value);
                else next.delete("branchId");
                setSearchParams(next);
              })
            }
          >
            <option value="">Todas las sucursales</option>
            {(branchesQuery.data?.content ?? []).map((branch) => (
              <option key={branch.id} value={branch.id}>
                {branch.name}
                {branch.id === user?.branchId ? " (mi sucursal)" : ""}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="alerts-status">Estado</label>
          <select id="alerts-status" value={status} onChange={(event) => changeFilter(() => setStatus(event.target.value as StatusFilter))}>
            <option value="ACTIVE">Activas</option>
            <option value="RESOLVED">Resueltas</option>
            <option value="TODAS">Todas</option>
          </select>
        </div>
      </form>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.content.length === 0}
        emptyTitle={status === "ACTIVE" ? "Ningún producto está por debajo de su stock mínimo." : "No hay alertas que coincidan con los filtros."}
      >
        {(result) => (
          <>
            <div style={{ overflowX: "auto" }}>
            <table>
              <caption>{result.totalElements} alerta(s)</caption>
              <thead>
                <tr>
                  <th scope="col">Producto</th>
                  <th scope="col">Sucursal</th>
                  <th scope="col">Stock</th>
                  <th scope="col">Mínimo</th>
                  <th scope="col">Estado</th>
                  <th scope="col">Disparada</th>
                  <th scope="col">Resuelta</th>
                  <th scope="col">Acciones</th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((alert) => (
                  <tr key={alert.id}>
                    <td>{alert.sku} — {alert.name}</td>
                    <td>{branchLabel(alert.branchId)}</td>
                    <td>{alert.quantityOnHand}</td>
                    <td>{alert.minimumStock}</td>
                    <td>
                      <span className={alert.status === "ACTIVE" ? "badge badge--warn" : "badge badge--ok"}>
                        {alert.status === "ACTIVE" ? "Activa" : "Resuelta"}
                      </span>
                    </td>
                    <td>{new Date(alert.triggeredAt).toLocaleString()}</td>
                    <td>{alert.resolvedAt ? new Date(alert.resolvedAt).toLocaleString() : "—"}</td>
                    <td>
                      <Link to={`/inventario?branchId=${alert.branchId}`}>Ver inventario</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
            <Pagination page={result} onPageChange={setPage} />
          </>
        )}
      </AsyncBoundary>
    </section>
  );
}
