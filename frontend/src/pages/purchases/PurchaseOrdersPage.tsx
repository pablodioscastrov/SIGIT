import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { cancelPurchaseOrder, listPurchaseOrders } from "../../api/endpoints/purchases";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { canPurchases } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { AsyncBoundary } from "../../components/state/states";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { Pagination } from "../../components/ui/Pagination";
import { formatCurrency } from "../../lib/currency";
import type { PurchaseOrder, PurchaseOrderStatus } from "../../types/api";
import { productLabel, useProductIndex } from "../products/useCatalog";
import { useActiveSuppliers } from "./useSuppliers";
import { PURCHASE_STATUS_LABELS, orderTotal } from "./statusLabels";

const PAGE_SIZE = 10;
const STATUSES: PurchaseOrderStatus[] = ["CREATED", "PARTIALLY_RECEIVED", "RECEIVED", "CANCELLED"];

type SortColumn = "supplier" | "product";
type SortDirection = "asc" | "desc";

/**
 * Listado de órdenes de compra. El filtro de sucursal solo tiene sentido
 * para `ADMIN`: para el resto de roles el backend siempre acota a la propia
 * sucursal (`PurchaseOrderService.list`), sin importar qué se envíe, así que
 * mostrar el selector a un `OPERATOR`/`MANAGER` solo generaría confusión.
 */
export function PurchaseOrdersPage() {
  const { user } = useAuth();
  const canWrite = canPurchases.write(user?.role);
  const isAdmin = user?.role === "ADMIN";

  const [branchId, setBranchId] = useState("");
  const [supplierId, setSupplierId] = useState("");
  const [status, setStatus] = useState<PurchaseOrderStatus | "">("");
  const [page, setPage] = useState(0);
  const [cancelling, setCancelling] = useState<PurchaseOrder | undefined>();
  const [sort, setSort] = useState<{ column: SortColumn; direction: SortDirection } | null>(null);

  const branchesQuery = useQuery({
    queryKey: queryKeys.branches({ active: true }),
    queryFn: () => listBranches({ active: true }),
  });
  const branchesById = new Map((branchesQuery.data?.content ?? []).map((branch) => [branch.id, branch]));

  const { byId: suppliersById, suppliers } = useActiveSuppliers();
  const { byId: productsById } = useProductIndex();

  function productsLabel(order: PurchaseOrder): string {
    return order.items.map((item) => productLabel(productsById.get(item.productId), item.productId)).join(", ");
  }

  function toggleSort(column: SortColumn) {
    setSort((current) => {
      if (current?.column !== column) return { column, direction: "asc" };
      return current.direction === "asc" ? { column, direction: "desc" } : null;
    });
  }

  function sortIndicator(column: SortColumn): string {
    if (sort?.column !== column) return "";
    return sort.direction === "asc" ? " ▲" : " ▼";
  }

  const params = {
    branchId: isAdmin ? branchId || undefined : undefined,
    supplierId: supplierId || undefined,
    status: status || undefined,
    page,
    size: PAGE_SIZE,
  };
  const query = useQuery({
    queryKey: queryKeys.purchaseOrders(params),
    queryFn: () => listPurchaseOrders(params),
  });

  const queryClient = useQueryClient();
  const cancelMutation = useMutation({
    mutationFn: (order: PurchaseOrder) => cancelPurchaseOrder(order.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.purchaseOrders });
      setCancelling(undefined);
    },
  });

  function changeFilter(apply: () => void) {
    apply();
    setPage(0);
  }

  return (
    <section>
      <div className="page__header">
        <h1>Compras</h1>
        {canWrite ? (
          <Link to="/compras/nueva">
            <button type="button">Nueva orden</button>
          </Link>
        ) : null}
      </div>

      <form className="filters" onSubmit={(event) => event.preventDefault()}>
        {isAdmin ? (
          <div className="field">
            <label htmlFor="purchases-branch">Sucursal</label>
            <select id="purchases-branch" value={branchId} onChange={(event) => changeFilter(() => setBranchId(event.target.value))}>
              <option value="">Todas las sucursales</option>
              {(branchesQuery.data?.content ?? []).map((branch) => (
                <option key={branch.id} value={branch.id}>
                  {branch.name}
                </option>
              ))}
            </select>
          </div>
        ) : null}
        <div className="field">
          <label htmlFor="purchases-supplier">Proveedor</label>
          <select id="purchases-supplier" value={supplierId} onChange={(event) => changeFilter(() => setSupplierId(event.target.value))}>
            <option value="">Todos</option>
            {suppliers.map((supplier) => (
              <option key={supplier.id} value={supplier.id}>
                {supplier.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="purchases-status">Estado</label>
          <select id="purchases-status" value={status} onChange={(event) => changeFilter(() => setStatus(event.target.value as PurchaseOrderStatus))}>
            <option value="">Todos</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {PURCHASE_STATUS_LABELS[value]}
              </option>
            ))}
          </select>
        </div>
      </form>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.content.length === 0}
        emptyTitle="No hay órdenes de compra que coincidan con los filtros."
      >
        {(result) => {
          const sortedContent = [...result.content];
          if (sort) {
            const key = (order: PurchaseOrder) =>
              sort.column === "supplier" ? suppliersById.get(order.supplierId)?.name ?? order.supplierId : productsLabel(order);
            sortedContent.sort((a, b) => {
              const comparison = key(a).localeCompare(key(b), "es", { sensitivity: "base" });
              return sort.direction === "asc" ? comparison : -comparison;
            });
          }
          return (
          <>
            <div style={{ overflowX: "auto" }}>
            <table>
              <caption>{result.totalElements} orden(es) de compra{sort ? " · orden alfabético dentro de esta página" : ""}</caption>
              <thead>
                <tr>
                  <th scope="col">Orden</th>
                  <th scope="col">
                    <button type="button" className="th-sort" onClick={() => toggleSort("supplier")}>
                      Proveedor{sortIndicator("supplier")}
                    </button>
                  </th>
                  <th scope="col">
                    <button type="button" className="th-sort" onClick={() => toggleSort("product")}>
                      Producto{sortIndicator("product")}
                    </button>
                  </th>
                  <th scope="col">Sucursal</th>
                  <th scope="col">Fecha</th>
                  <th scope="col">Condición de pago</th>
                  <th scope="col">Estado</th>
                  <th scope="col">Total</th>
                  <th scope="col">Acciones</th>
                </tr>
              </thead>
              <tbody>
                {sortedContent.map((order) => (
                  <tr key={order.id}>
                    <td>{order.orderNumber}</td>
                    <td>{suppliersById.get(order.supplierId)?.name ?? order.supplierId}</td>
                    <td>{productsLabel(order)}</td>
                    <td>{branchesById.get(order.branchId)?.name ?? order.branchId}</td>
                    <td>{new Date(order.orderDate).toLocaleDateString()}</td>
                    <td>{order.paymentTerm ?? "—"}</td>
                    <td>
                      <span className={`badge ${order.status === "CANCELLED" ? "badge--muted" : "badge--ok"}`}>
                        {PURCHASE_STATUS_LABELS[order.status]}
                      </span>
                    </td>
                    <td>{formatCurrency(orderTotal(order.items))}</td>
                    <td className="row__actions">
                      <Link to={`/compras/${order.id}`}>Ver</Link>
                      {canWrite && order.status === "CREATED" ? (
                        <button type="button" onClick={() => setCancelling(order)}>
                          Cancelar
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
            <Pagination page={result} onPageChange={setPage} />
          </>
          );
        }}
      </AsyncBoundary>

      {cancelling ? (
        <ConfirmDialog
          title="Cancelar orden de compra"
          message={`La orden ${cancelling.orderNumber} pasará a Cancelada y ya no podrá recibirse mercancía contra ella.`}
          confirmLabel="Cancelar orden"
          isPending={cancelMutation.isPending}
          error={cancelMutation.error}
          onConfirm={() => cancelMutation.mutate(cancelling)}
          onCancel={() => setCancelling(undefined)}
        />
      ) : null}
    </section>
  );
}
