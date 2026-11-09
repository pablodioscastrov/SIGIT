import { useQueries, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { listMovements } from "../../api/endpoints/inventory";
import { listProductUnits } from "../../api/endpoints/products";
import { exportInventoryMovements } from "../../api/endpoints/reports";
import { queryKeys } from "../../api/queryClient";
import { AsyncBoundary } from "../../components/state/states";
import { ExportDialog } from "../../components/ui/ExportDialog";
import { Pagination } from "../../components/ui/Pagination";
import type { InventoryMovement, MovementReason } from "../../types/api";
import { productLabel, useProductIndex, useUnitsOfMeasure } from "../products/useCatalog";

const PAGE_SIZE = 20;

const REASONS: MovementReason[] = [
  "COMPRA",
  "DEVOLUCION",
  "AJUSTE_INGRESO",
  "VENTA",
  "MERMA",
  "AJUSTE_RETIRO",
  "TRANSFERENCIA_SALIDA",
  "TRANSFERENCIA_ENTRADA",
];

const SOURCE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: "Orden de compra",
  SALE: "Venta",
  TRANSFER: "Transferencia",
};

/**
 * Historial de movimientos: la traza de por qué el stock vale lo que vale.
 * Es de solo lectura por diseño — un movimiento no se edita ni se borra
 * (BR-023); corregir un error significa registrar un ajuste que lo compense.
 *
 * <p>Los filtros llegan por la URL para que "ver los movimientos de este
 * producto en esta sucursal" sea un enlace desde la tabla de inventario.
 */
export function MovementsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const branchId = searchParams.get("branchId") ?? "";
  const productId = searchParams.get("productId") ?? "";
  const reason = searchParams.get("reason") ?? "";
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);

  const branchesQuery = useQuery({
    queryKey: queryKeys.branches({ active: true }),
    queryFn: () => listBranches({ active: true }),
  });
  const branchesById = new Map((branchesQuery.data?.content ?? []).map((branch) => [branch.id, branch]));

  const { byId: productsById, products } = useProductIndex();
  const { byId: unitsById } = useUnitsOfMeasure();

  const params = {
    branchId: branchId || undefined,
    productId: productId || undefined,
    reason: reason || undefined,
    page,
    size: PAGE_SIZE,
  };
  const query = useQuery({
    queryKey: queryKeys.inventoryMovements(params),
    queryFn: () => listMovements(params),
  });

  // Un movimiento guarda la cantidad tal como se transaccionó (1 "Lote"),
  // nunca la reescribe en unidad base — correcto para trazabilidad (BR-001),
  // pero por sí solo parece "no convertir" al compararlo contra el stock
  // agregado, que sí está en base. Se resuelve el factor de cada producto
  // presente en la página actual para mostrar el equivalente al lado.
  const movementProductIds = Array.from(new Set((query.data?.content ?? []).map((movement) => movement.productId)));
  const productUnitQueries = useQueries({
    queries: movementProductIds.map((id) => ({
      queryKey: queryKeys.productUnits(id),
      queryFn: () => listProductUnits(id),
      staleTime: 60_000,
    })),
  });
  const productUnitsByProductId = new Map(
    movementProductIds.map((id, index) => [id, productUnitQueries[index]?.data ?? []]),
  );

  function baseUnitEquivalent(movement: InventoryMovement): string | null {
    const units = productUnitsByProductId.get(movement.productId) ?? [];
    const line = units.find((unit) => unit.unitOfMeasureId === movement.unitOfMeasureId);
    if (!line || line.baseUnit) return null;
    const baseUnit = units.find((unit) => unit.baseUnit);
    if (!baseUnit) return null;
    const baseQuantity = Number((movement.quantity * line.conversionFactorToBase).toFixed(6));
    return `${baseQuantity} ${baseUnit.unitCode}`;
  }

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    setSearchParams(next, { replace: true });
    setPage(0);
  }

  return (
    <section>
      <div className="page__header">
        <h1>Movimientos de inventario</h1>
        <div className="page__actions">
          <button type="button" onClick={() => setExporting(true)}>
            Exportar a Excel
          </button>
          <Link to="/inventario">Volver a inventario</Link>
        </div>
      </div>

      {exporting ? (
        <ExportDialog
          title="Exportar movimientos de inventario"
          onClose={() => setExporting(false)}
          onExport={(range) =>
            exportInventoryMovements({
              branchId: branchId || undefined,
              productId: productId || undefined,
              reason: reason || undefined,
              ...range,
            })
          }
        />
      ) : null}

      <form className="filters" onSubmit={(event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="movements-branch">Sucursal</label>
          <select id="movements-branch" value={branchId} onChange={(event) => setFilter("branchId", event.target.value)}>
            <option value="">Todas</option>
            {(branchesQuery.data?.content ?? []).map((branch) => (
              <option key={branch.id} value={branch.id}>
                {branch.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="movements-product">Producto</label>
          <select id="movements-product" value={productId} onChange={(event) => setFilter("productId", event.target.value)}>
            <option value="">Todos</option>
            {products.map((product) => (
              <option key={product.id} value={product.id}>
                {product.sku} — {product.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="movements-reason">Motivo</label>
          <select id="movements-reason" value={reason} onChange={(event) => setFilter("reason", event.target.value)}>
            <option value="">Todos</option>
            {REASONS.map((value) => (
              <option key={value} value={value}>
                {value}
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
        emptyTitle="No hay movimientos que coincidan con los filtros."
      >
        {(result) => (
          <>
            <div style={{ overflowX: "auto" }}>
            <table>
              <caption>{result.totalElements} movimiento(s), del más reciente al más antiguo</caption>
              <thead>
                <tr>
                  <th scope="col">Fecha</th>
                  <th scope="col">Producto</th>
                  <th scope="col">Sucursal</th>
                  <th scope="col">Tipo</th>
                  <th scope="col">Motivo</th>
                  <th scope="col">Cantidad</th>
                  <th scope="col">Origen</th>
                  <th scope="col">Notas</th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((movement) => (
                  <tr key={movement.id}>
                    <td>{new Date(movement.occurredAt).toLocaleString()}</td>
                    <td>{productLabel(productsById.get(movement.productId), movement.productId)}</td>
                    <td>{branchesById.get(movement.branchId)?.name ?? movement.branchId}</td>
                    <td>{movement.direction === "INGRESO" ? "Ingreso" : "Retiro"}</td>
                    <td>{movement.reason}</td>
                    <td>
                      {movement.direction === "INGRESO" ? "+" : "−"}
                      {movement.quantity} {unitsById.get(movement.unitOfMeasureId)?.code ?? ""}
                      {(() => {
                        const equivalent = baseUnitEquivalent(movement);
                        return equivalent ? <p className="state__hint">= {equivalent}</p> : null;
                      })()}
                    </td>
                    <td>
                      {movement.source
                        ? `${SOURCE_LABELS[movement.source.type] ?? movement.source.type} #${movement.source.id}`
                        : "Ajuste manual"}
                    </td>
                    <td>{movement.notes ?? "—"}</td>
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
