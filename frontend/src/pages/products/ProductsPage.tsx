import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { listProducts, setProductActive } from "../../api/endpoints/products";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { can } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { AsyncBoundary } from "../../components/state/states";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { Pagination } from "../../components/ui/Pagination";
import { useDebouncedValue } from "../../hooks/useDebouncedValue";
import { formatCurrency } from "../../lib/currency";
import type { Product } from "../../types/api";
import { ProductFormDialog } from "./ProductFormDialog";
import { ProductUnitsDialog } from "./ProductUnitsDialog";
import { useUnitsOfMeasure } from "./useCatalog";

type StatusFilter = "todos" | "activos" | "inactivos";

const PAGE_SIZE = 10;

export function ProductsPage() {
  const { user } = useAuth();
  const canWrite = can.writeProducts(user?.role);

  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<StatusFilter>("todos");
  const [page, setPage] = useState(0);
  const debouncedSearch = useDebouncedValue(search);

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Product | undefined>();
  const [managingUnits, setManagingUnits] = useState<Product | undefined>();
  const [toggling, setToggling] = useState<Product | undefined>();

  const { units, byId: unitsById } = useUnitsOfMeasure();

  const params = {
    search: debouncedSearch.trim() || undefined,
    active: status === "todos" ? undefined : status === "activos",
    page,
    size: PAGE_SIZE,
  };
  const query = useQuery({
    queryKey: queryKeys.products(params),
    queryFn: () => listProducts(params),
  });

  const queryClient = useQueryClient();
  const toggleMutation = useMutation({
    mutationFn: (product: Product) => setProductActive(product.id, !product.active),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.products });
      setToggling(undefined);
    },
  });

  function changeFilter(apply: () => void) {
    apply();
    setPage(0);
  }

  return (
    <section>
      <div className="page__header">
        <h1>Productos</h1>
        <div className="page__actions">
          <Link to="/productos/unidades">Unidades de medida</Link>
          {canWrite ? (
            <button type="button" onClick={() => setCreating(true)}>
              Nuevo producto
            </button>
          ) : null}
        </div>
      </div>

      <form className="filters" role="search" onSubmit={(event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="products-search">Buscar por SKU o nombre</label>
          <input
            id="products-search"
            type="search"
            value={search}
            onChange={(event) => changeFilter(() => setSearch(event.target.value))}
          />
        </div>
        <div className="field">
          <label htmlFor="products-status">Estado</label>
          <select
            id="products-status"
            value={status}
            onChange={(event) => changeFilter(() => setStatus(event.target.value as StatusFilter))}
          >
            <option value="todos">Todos</option>
            <option value="activos">Activos</option>
            <option value="inactivos">Inactivos</option>
          </select>
        </div>
      </form>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.content.length === 0}
        emptyTitle="No hay productos que coincidan con los filtros."
      >
        {(result) => (
          <>
            <div style={{ overflowX: "auto" }}>
            <table>
              <caption>{result.totalElements} producto(s)</caption>
              <thead>
                <tr>
                  <th scope="col">SKU</th>
                  <th scope="col">Nombre</th>
                  <th scope="col">Unidad base</th>
                  <th scope="col">Stock mínimo</th>
                  <th scope="col">Precio de venta</th>
                  <th scope="col">Estado</th>
                  <th scope="col">Acciones</th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((product) => (
                  <tr key={product.id}>
                    <td>{product.sku}</td>
                    <td>
                      {product.name}
                      {product.description ? <p className="state__hint">{product.description}</p> : null}
                    </td>
                    <td>{unitsById.get(product.baseUnitOfMeasureId)?.code ?? product.baseUnitOfMeasureId}</td>
                    <td>{product.minimumStock}</td>
                    <td>{product.salePrice != null ? formatCurrency(product.salePrice) : "Sin precio"}</td>
                    <td>
                      <span className={product.active ? "badge badge--ok" : "badge badge--muted"}>
                        {product.active ? "Activo" : "Inactivo"}
                      </span>
                    </td>
                    <td className="row__actions">
                      <button type="button" onClick={() => setManagingUnits(product)}>
                        Unidades
                      </button>
                      {canWrite ? (
                        <>
                          <button type="button" onClick={() => setEditing(product)}>
                            Editar
                          </button>
                          <button type="button" onClick={() => setToggling(product)}>
                            {product.active ? "Desactivar" : "Activar"}
                          </button>
                        </>
                      ) : null}
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

      {creating ? <ProductFormDialog units={units} onClose={() => setCreating(false)} /> : null}
      {editing ? (
        <ProductFormDialog product={editing} units={units} onClose={() => setEditing(undefined)} />
      ) : null}
      {managingUnits ? (
        <ProductUnitsDialog
          product={managingUnits}
          units={units}
          canWrite={canWrite}
          onClose={() => setManagingUnits(undefined)}
        />
      ) : null}
      {toggling ? (
        <ConfirmDialog
          title={toggling.active ? "Desactivar producto" : "Activar producto"}
          message={
            toggling.active
              ? `${toggling.sku} — ${toggling.name} dejará de estar disponible para nuevas operaciones. No se elimina: su historial y su inventario se conservan.`
              : `${toggling.sku} — ${toggling.name} volverá a estar disponible para nuevas operaciones.`
          }
          confirmLabel={toggling.active ? "Desactivar" : "Activar"}
          isPending={toggleMutation.isPending}
          error={toggleMutation.error}
          onConfirm={() => toggleMutation.mutate(toggling)}
          onCancel={() => setToggling(undefined)}
        />
      ) : null}
    </section>
  );
}
