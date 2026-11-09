import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getReplenishment } from "../../api/endpoints/dashboard";
import { queryKeys } from "../../api/queryClient";
import { AsyncBoundary } from "../../components/state/states";

/** Productos próximos a agotarse (RF-034, BR-042) — mismo umbral que el filtro `lowStock` de Inventario. */
export function ReplenishmentPanel({ branchId }: { branchId: string }) {
  const query = useQuery({
    queryKey: queryKeys.dashboardReplenishment({ branchId }),
    queryFn: () => getReplenishment({ branchId }),
  });

  return (
    <section className="panel dashboard-panel">
      <div className="page__header">
        <h2>Reabastecimiento</h2>
        <div className="page__actions">
          <Link to={`/inventario?branchId=${branchId}`}>Ver inventario</Link>
          <Link to={`/inventario/alertas?branchId=${branchId}`}>Ver alertas</Link>
        </div>
      </div>
      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.mostUrgent.length === 0}
        emptyTitle="Ningún producto está por debajo de su stock mínimo."
      >
        {(result) => (
          <>
            <p>
              <strong>{result.lowStockCount}</strong> producto(s) bajo el umbral de reabastecimiento.
            </p>
            <div style={{ overflowX: "auto" }}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Producto</th>
                  <th scope="col">Stock</th>
                  <th scope="col">Mínimo</th>
                </tr>
              </thead>
              <tbody>
                {result.mostUrgent.map((entry) => (
                  <tr key={entry.productId}>
                    <td>{entry.sku ?? entry.productId} — {entry.name ?? "—"}</td>
                    <td>{entry.quantityOnHand}</td>
                    <td>{entry.minimumStock}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          </>
        )}
      </AsyncBoundary>
    </section>
  );
}
