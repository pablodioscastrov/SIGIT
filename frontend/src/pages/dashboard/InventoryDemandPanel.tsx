import { useQuery } from "@tanstack/react-query";
import { getInventoryRotation } from "../../api/endpoints/dashboard";
import { queryKeys } from "../../api/queryClient";
import { AsyncBoundary } from "../../components/state/states";
import type { ProductDemandEntry } from "../../types/api";

function DemandTable({ title, entries, emptyText }: { title: string; entries: ProductDemandEntry[]; emptyText: string }) {
  return (
    <div>
      <h3>{title}</h3>
      {entries.length === 0 ? (
        <p className="state__hint">{emptyText}</p>
      ) : (
        <div style={{ overflowX: "auto" }}>
        <table>
          <thead>
            <tr>
              <th scope="col">Producto</th>
              <th scope="col">Vendido</th>
              <th scope="col">Stock actual</th>
              <th scope="col">Rotación</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.productId}>
                <td>{entry.sku ?? entry.productId} — {entry.name ?? "—"}</td>
                <td>{entry.unitsSold}</td>
                <td>{entry.currentStock}</td>
                <td>{entry.turnoverRatio === null ? "—" : entry.turnoverRatio}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
    </div>
  );
}

/**
 * Rotación de inventario y demanda alta/baja (RF-032, BR-040). La rotación
 * mostrada es `unidades vendidas ÷ stock actual` — una aproximación
 * documentada, no el promedio de inventario del período (el sistema no
 * guarda esa serie histórica); "—" cuando el stock actual es 0, nunca un
 * número inventado.
 */
export function InventoryDemandPanel({ branchId, months }: { branchId: string; months: number }) {
  const params = { branchId, months };
  const query = useQuery({ queryKey: queryKeys.dashboardDemand(params), queryFn: () => getInventoryRotation(params) });

  return (
    <section className="panel dashboard-panel">
      <h2>Rotación y demanda</h2>
      <AsyncBoundary isLoading={query.isPending} error={query.error} data={query.data} onRetry={() => query.refetch()}>
        {(demand) => (
          <div className="dashboard-panel__columns">
            <DemandTable title="Alta demanda" entries={demand.topDemand} emptyText="Sin productos con inventario en esta sucursal." />
            <DemandTable title="Baja demanda" entries={demand.lowDemand} emptyText="Sin productos con inventario en esta sucursal." />
          </div>
        )}
      </AsyncBoundary>
    </section>
  );
}
