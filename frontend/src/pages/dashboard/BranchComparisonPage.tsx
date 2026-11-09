import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getBranchComparison } from "../../api/endpoints/dashboard";
import { queryKeys } from "../../api/queryClient";
import { AsyncBoundary } from "../../components/state/states";
import { formatCurrency } from "../../lib/currency";

/**
 * Comparativa entre sucursales (RF-035, BR-043) — solo `MANAGER`/`ADMIN`; la
 * ruta ya está protegida con `RequireRole` (un `OPERATOR` que la fuerce cae
 * en "Sin permiso" antes de llegar aquí). No es un promedio ni un ranking
 * nuevo: son las mismas cifras que cada sucursal ya expone en su propio
 * dashboard, yuxtapuestas para comparar.
 */
export function BranchComparisonPage() {
  const query = useQuery({ queryKey: queryKeys.dashboardBranchComparison(), queryFn: getBranchComparison });

  return (
    <section>
      <div className="page__header">
        <h1>Comparativa entre sucursales</h1>
        <Link to="/dashboard">Volver al dashboard</Link>
      </div>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.branches.length === 0}
        emptyTitle="No hay sucursales activas para comparar."
      >
        {(result) => (
          <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                <th scope="col">Sucursal</th>
                <th scope="col">Ventas del mes</th>
                <th scope="col">Transferencias activas</th>
                <th scope="col">Productos bajo mínimo</th>
              </tr>
            </thead>
            <tbody>
              {result.branches.map((branch) => (
                <tr key={branch.branchId}>
                  <td>{branch.branchName}</td>
                  <td>{formatCurrency(branch.currentMonthSales)}</td>
                  <td>{branch.activeTransfersCount}</td>
                  <td>{branch.lowStockCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </AsyncBoundary>
    </section>
  );
}
