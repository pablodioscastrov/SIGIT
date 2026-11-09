import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { exportSales } from "../../api/endpoints/reports";
import { listSales } from "../../api/endpoints/sales";
import { queryKeys } from "../../api/queryClient";
import { canSales } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { AsyncBoundary } from "../../components/state/states";
import { ExportDialog } from "../../components/ui/ExportDialog";
import { Pagination } from "../../components/ui/Pagination";
import { formatCurrency } from "../../lib/currency";

const PAGE_SIZE = 10;

/** Listado de ventas. Igual que en compras, el filtro de sucursal solo se
 * ofrece a `ADMIN`: para cualquier otro rol el backend siempre acota a la
 * propia sucursal (`SaleService.list`). */
export function SalesPage() {
  const { user } = useAuth();
  const canWrite = canSales.write(user?.role);
  const isAdmin = user?.role === "ADMIN";

  const [branchId, setBranchId] = useState("");
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);

  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const branchesById = new Map((branchesQuery.data?.content ?? []).map((branch) => [branch.id, branch]));

  const params = { branchId: isAdmin ? branchId || undefined : undefined, page, size: PAGE_SIZE };
  const query = useQuery({ queryKey: queryKeys.sales(params), queryFn: () => listSales(params) });

  return (
    <section>
      <div className="page__header">
        <h1>Ventas</h1>
        <div className="page__actions">
          <button type="button" onClick={() => setExporting(true)}>
            Exportar a Excel
          </button>
          {canWrite ? (
            <Link to="/ventas/nueva">
              <button type="button">Nueva venta</button>
            </Link>
          ) : null}
        </div>
      </div>

      {exporting ? (
        <ExportDialog
          title="Exportar ventas"
          onClose={() => setExporting(false)}
          onExport={(range) => exportSales({ branchId: isAdmin ? branchId || undefined : undefined, ...range })}
        />
      ) : null}

      {isAdmin ? (
        <form className="filters" onSubmit={(event) => event.preventDefault()}>
          <div className="field">
            <label htmlFor="sales-branch">Sucursal</label>
            <select
              id="sales-branch"
              value={branchId}
              onChange={(event) => {
                setBranchId(event.target.value);
                setPage(0);
              }}
            >
              <option value="">Todas las sucursales</option>
              {(branchesQuery.data?.content ?? []).map((branch) => (
                <option key={branch.id} value={branch.id}>
                  {branch.name}
                </option>
              ))}
            </select>
          </div>
        </form>
      ) : null}

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.content.length === 0}
        emptyTitle="No hay ventas registradas todavía."
      >
        {(result) => (
          <>
            <div style={{ overflowX: "auto" }}>
            <table>
              <caption>{result.totalElements} venta(s)</caption>
              <thead>
                <tr>
                  <th scope="col">Venta</th>
                  <th scope="col">Sucursal</th>
                  <th scope="col">Fecha</th>
                  <th scope="col">Responsable</th>
                  <th scope="col">Total</th>
                  <th scope="col">Acciones</th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((sale) => (
                  <tr key={sale.id}>
                    <td>{sale.saleNumber}</td>
                    <td>{branchesById.get(sale.branchId)?.name ?? sale.branchId}</td>
                    <td>{new Date(sale.saleDate).toLocaleString()}</td>
                    <td>{sale.soldByUserName ?? sale.soldByUserId}</td>
                    <td>{formatCurrency(sale.total)}</td>
                    <td>
                      <Link to={`/ventas/${sale.id}`}>Ver comprobante</Link>
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
