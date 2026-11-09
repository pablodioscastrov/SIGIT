import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { deleteSupplier, listSuppliers, setSupplierActive } from "../../api/endpoints/suppliers";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { canSuppliers } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { AsyncBoundary } from "../../components/state/states";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { Pagination } from "../../components/ui/Pagination";
import { useDebouncedValue } from "../../hooks/useDebouncedValue";
import type { Supplier } from "../../types/api";
import { SupplierFormDialog } from "./SupplierFormDialog";

const PAGE_SIZE = 20;
type StatusFilter = "activos" | "inactivos" | "todos";

/**
 * Proveedores (BR-058): lectura abierta a cualquier rol; crear/editar/
 * activar/desactivar es `MANAGER`+`ADMIN` (`OPERATOR` solo lectura);
 * eliminar (real, no reversible) es exclusivo de `ADMIN`. El backend la
 * rechaza además con `PROVEEDOR_CON_DATOS_ASOCIADOS` si el proveedor tiene
 * órdenes de compra asociadas, mensaje que se muestra tal cual en vez de
 * reinterpretarse aquí.
 */
export function SuppliersPage() {
  const { user } = useAuth();
  const canWrite = canSuppliers.write(user?.role);
  const canDelete = canSuppliers.delete(user?.role);

  const [search, setSearch] = useState("");
  const debouncedSearch = useDebouncedValue(search);
  const [status, setStatus] = useState<StatusFilter>("activos");
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Supplier | undefined>();
  const [toggling, setToggling] = useState<Supplier | undefined>();
  const [deleting, setDeleting] = useState<Supplier | undefined>();

  const params = {
    search: debouncedSearch.trim() || undefined,
    active: status === "todos" ? undefined : status === "activos",
    page,
    size: PAGE_SIZE,
  };
  const query = useQuery({
    queryKey: queryKeys.suppliers(params),
    queryFn: () => listSuppliers(params),
  });

  const queryClient = useQueryClient();
  const toggleMutation = useMutation({
    mutationFn: (supplier: Supplier) => setSupplierActive(supplier.id, !supplier.active),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.suppliers });
      setToggling(undefined);
    },
  });
  const deleteMutation = useMutation({
    mutationFn: (supplier: Supplier) => deleteSupplier(supplier.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.suppliers });
      setDeleting(undefined);
    },
  });

  function changeFilter(apply: () => void) {
    apply();
    setPage(0);
  }

  return (
    <section>
      <div className="page__header">
        <h1>Proveedores</h1>
        {canWrite ? (
          <button type="button" onClick={() => setCreating(true)}>
            Nuevo proveedor
          </button>
        ) : null}
      </div>

      <form className="filters" role="search" onSubmit={(event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="suppliers-search">Buscar</label>
          <input
            id="suppliers-search"
            value={search}
            onChange={(event) => changeFilter(() => setSearch(event.target.value))}
            placeholder="Nombre o identificación fiscal"
          />
        </div>
        <div className="field">
          <label htmlFor="suppliers-status">Estado</label>
          <select id="suppliers-status" value={status} onChange={(event) => changeFilter(() => setStatus(event.target.value as StatusFilter))}>
            <option value="activos">Activos</option>
            <option value="inactivos">Inactivos</option>
            <option value="todos">Todos</option>
          </select>
        </div>
      </form>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.content.length === 0}
        emptyTitle={
          status === "activos"
            ? "No hay proveedores activos que coincidan."
            : status === "inactivos"
              ? "No hay proveedores inactivos que coincidan."
              : "No hay proveedores que coincidan con los filtros."
        }
      >
        {(result) => (
          <>
            <div style={{ overflowX: "auto" }}>
            <table>
              <caption>{result.totalElements} proveedor(es)</caption>
              <thead>
                <tr>
                  <th scope="col">Identificación fiscal</th>
                  <th scope="col">Razón social</th>
                  <th scope="col">Contacto</th>
                  <th scope="col">Teléfono</th>
                  <th scope="col">Correo</th>
                  <th scope="col">Estado</th>
                  {canWrite || canDelete ? <th scope="col">Acciones</th> : null}
                </tr>
              </thead>
              <tbody>
                {result.content.map((supplier) => (
                  <tr key={supplier.id}>
                    <td>{supplier.taxId}</td>
                    <td>{supplier.name}</td>
                    <td>{supplier.contactName ?? "—"}</td>
                    <td>{supplier.phone ?? "—"}</td>
                    <td>{supplier.email ?? "—"}</td>
                    <td>
                      <span className={supplier.active ? "badge badge--ok" : "badge badge--muted"}>
                        {supplier.active ? "Activo" : "Inactivo"}
                      </span>
                    </td>
                    {canWrite || canDelete ? (
                      <td className="row__actions">
                        {canWrite ? (
                          <>
                            <button type="button" onClick={() => setEditing(supplier)}>
                              Editar
                            </button>
                            <button type="button" onClick={() => setToggling(supplier)}>
                              {supplier.active ? "Desactivar" : "Activar"}
                            </button>
                          </>
                        ) : null}
                        {canDelete ? (
                          <button type="button" className="button--danger" onClick={() => setDeleting(supplier)}>
                            Eliminar
                          </button>
                        ) : null}
                      </td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
            <Pagination page={result} onPageChange={setPage} />
          </>
        )}
      </AsyncBoundary>

      {creating ? <SupplierFormDialog onClose={() => setCreating(false)} /> : null}
      {editing ? <SupplierFormDialog supplier={editing} onClose={() => setEditing(undefined)} /> : null}
      {toggling ? (
        <ConfirmDialog
          title={toggling.active ? "Desactivar proveedor" : "Activar proveedor"}
          message={
            toggling.active
              ? `${toggling.taxId} — ${toggling.name} dejará de estar disponible para nuevas órdenes de compra. No se elimina: sus datos se conservan.`
              : `${toggling.taxId} — ${toggling.name} volverá a estar disponible para nuevas órdenes de compra.`
          }
          confirmLabel={toggling.active ? "Desactivar" : "Activar"}
          isPending={toggleMutation.isPending}
          error={toggleMutation.error}
          onConfirm={() => toggleMutation.mutate(toggling)}
          onCancel={() => setToggling(undefined)}
        />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          title="Eliminar proveedor"
          message={`${deleting.taxId} — ${deleting.name} se eliminará permanentemente. Si tiene órdenes de compra asociadas, la operación se rechazará: desactívalo en su lugar.`}
          confirmLabel="Eliminar"
          isPending={deleteMutation.isPending}
          error={deleteMutation.error}
          onConfirm={() => deleteMutation.mutate(deleting)}
          onCancel={() => setDeleting(undefined)}
        />
      ) : null}
    </section>
  );
}
