import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { listInventory } from "../../api/endpoints/inventory";
import { queryKeys } from "../../api/queryClient";
import { can, canWriteInBranch } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { AsyncBoundary } from "../../components/state/states";
import { Pagination } from "../../components/ui/Pagination";
import { useDebouncedValue } from "../../hooks/useDebouncedValue";
import { formatCurrency } from "../../lib/currency";
import type { InventoryRow } from "../../types/api";
import { productLabel, useProductIndex } from "../products/useCatalog";
import { AdjustmentDialog } from "./AdjustmentDialog";
import { needsReplenishment } from "./replenishment";

const PAGE_SIZE = 10;

/**
 * Inventario por sucursal.
 *
 * <p>El selector de sucursal está disponible para cualquier rol porque la
 * <i>lectura</i> de inventario es abierta a todas las sucursales por requisito
 * (RF-002/RF-003, docs/API_DESIGN.md sección 6). Lo que sí está acotado es la
 * <i>escritura</i>: un ajuste manual solo se ofrece sobre la sucursal propia,
 * salvo ADMIN. El backend vuelve a comprobarlo en cualquier caso.
 */
export function InventoryPage() {
  const { user } = useAuth();
  const [branchId, setBranchId] = useState<string>(user?.branchId ?? "");
  const [search, setSearch] = useState("");
  const [lowStock, setLowStock] = useState(false);
  const [page, setPage] = useState(0);
  const [adjusting, setAdjusting] = useState<InventoryRow | undefined>();
  const debouncedSearch = useDebouncedValue(search);

  const branchesQuery = useQuery({
    queryKey: queryKeys.branches({ active: true }),
    queryFn: () => listBranches({ active: true }),
  });
  const branchesById = new Map((branchesQuery.data?.content ?? []).map((branch) => [branch.id, branch]));
  const branchLabel = (id: string) => branchesById.get(id)?.name ?? `Sucursal ${id}`;

  const { byId: productsById } = useProductIndex();

  const params = {
    branchId: branchId || undefined,
    search: debouncedSearch.trim() || undefined,
    lowStock: lowStock || undefined,
    page,
    size: PAGE_SIZE,
  };
  const query = useQuery({
    queryKey: queryKeys.inventory(params),
    queryFn: () => listInventory(params),
  });

  function changeFilter(apply: () => void) {
    apply();
    setPage(0);
  }

  return (
    <section>
      <div className="page__header">
        <h1>Inventario</h1>
        <Link to="/inventario/movimientos">Historial de movimientos</Link>
      </div>

      <form className="filters" role="search" onSubmit={(event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="inventory-branch">Sucursal</label>
          <select
            id="inventory-branch"
            value={branchId}
            onChange={(event) => changeFilter(() => setBranchId(event.target.value))}
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
          <label htmlFor="inventory-search">Buscar producto</label>
          <input
            id="inventory-search"
            type="search"
            value={search}
            onChange={(event) => changeFilter(() => setSearch(event.target.value))}
          />
        </div>
        <div className="field field--check">
          <label htmlFor="inventory-low-stock">
            <input
              id="inventory-low-stock"
              type="checkbox"
              checked={lowStock}
              onChange={(event) => changeFilter(() => setLowStock(event.target.checked))}
            />
            Solo por debajo del stock mínimo
          </label>
        </div>
      </form>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.content.length === 0}
        emptyTitle="No hay existencias que coincidan con los filtros."
      >
        {(result) => (
          <>
            <div style={{ overflowX: "auto" }}>
            <table>
              <caption>{result.totalElements} registro(s) de inventario</caption>
              <thead>
                <tr>
                  <th scope="col">Producto</th>
                  <th scope="col">Sucursal</th>
                  <th scope="col">Stock</th>
                  <th scope="col">Stock mínimo</th>
                  <th scope="col">Estado</th>
                  <th scope="col">Costo promedio</th>
                  <th scope="col">Acciones</th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((row) => {
                  const low = needsReplenishment(row);
                  const canAdjust = can.adjustInventory(user?.role) && canWriteInBranch(user, row.branchId);
                  return (
                    <tr key={row.id}>
                      <td>{productLabel(productsById.get(row.productId), row.productId)}</td>
                      <td>{branchLabel(row.branchId)}</td>
                      <td>{row.quantityOnHand}</td>
                      <td>{row.minimumStock}</td>
                      <td>
                        <span className={low ? "badge badge--warn" : "badge badge--ok"}>
                          {low ? "Reabastecer" : "Normal"}
                        </span>
                      </td>
                      <td>{formatCurrency(row.averageUnitCost)}</td>
                      <td className="row__actions">
                        <Link to={`/inventario/movimientos?branchId=${row.branchId}&productId=${row.productId}`}>
                          Movimientos
                        </Link>
                        {canAdjust ? (
                          <button type="button" onClick={() => setAdjusting(row)}>
                            Ajustar
                          </button>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            </div>
            <Pagination page={result} onPageChange={setPage} />
          </>
        )}
      </AsyncBoundary>

      {adjusting ? (
        <AdjustmentDialog
          row={adjusting}
          productLabel={productLabel(productsById.get(adjusting.productId), adjusting.productId)}
          branchLabel={branchLabel(adjusting.branchId)}
          onClose={() => setAdjusting(undefined)}
        />
      ) : null}
    </section>
  );
}
