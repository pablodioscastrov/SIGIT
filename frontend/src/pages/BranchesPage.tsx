import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { deleteBranch, listBranches, setBranchActive } from "../api/endpoints/branches";
import { queryKeys, queryPrefixes } from "../api/queryClient";
import { canBranches } from "../auth/permissions";
import { useAuth } from "../auth/useAuth";
import { AsyncBoundary } from "../components/state/states";
import { ConfirmDialog } from "../components/ui/ConfirmDialog";
import type { Branch } from "../types/api";
import { BranchFormDialog } from "./BranchFormDialog";

type StatusFilter = "activos" | "inactivos" | "todos";

/**
 * Consulta tipada + `AsyncBoundary` para carga/vacío/error, patrón de
 * referencia que siguen el resto de pantallas de negocio (ADR-010). Alta,
 * edición, activar/desactivar y eliminar (UC-15) son exclusivas de ADMIN.
 * Eliminar es una acción real (no reversible): el backend la rechaza con
 * `SUCURSAL_CON_DATOS_ASOCIADOS` si la sucursal tiene cualquier dato
 * asociado, mensaje que se muestra tal cual en vez de reinterpretarse aquí.
 */
export function BranchesPage() {
  const { user } = useAuth();
  const canWrite = canBranches.write(user?.role);

  const [status, setStatus] = useState<StatusFilter>("activos");
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Branch | undefined>();
  const [toggling, setToggling] = useState<Branch | undefined>();
  const [deleting, setDeleting] = useState<Branch | undefined>();

  const params = { active: status === "todos" ? undefined : status === "activos" };
  const query = useQuery({
    queryKey: queryKeys.branches(params),
    queryFn: () => listBranches(params),
  });

  const queryClient = useQueryClient();
  const toggleMutation = useMutation({
    mutationFn: (branch: Branch) => setBranchActive(branch.id, !branch.active),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.branches });
      setToggling(undefined);
    },
  });
  const deleteMutation = useMutation({
    mutationFn: (branch: Branch) => deleteBranch(branch.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.branches });
      setDeleting(undefined);
    },
  });

  return (
    <section>
      <div className="page__header">
        <h1>Sucursales</h1>
        {canWrite ? (
          <button type="button" onClick={() => setCreating(true)}>
            Nueva sucursal
          </button>
        ) : null}
      </div>

      <form className="filters" role="search" onSubmit={(event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="branches-status">Estado</label>
          <select id="branches-status" value={status} onChange={(event) => setStatus(event.target.value as StatusFilter)}>
            <option value="activos">Activas</option>
            <option value="inactivos">Inactivas</option>
            <option value="todos">Todas</option>
          </select>
        </div>
      </form>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(page) => page.content.length === 0}
        emptyTitle={
          status === "activos"
            ? "No hay sucursales activas registradas."
            : status === "inactivos"
              ? "No hay sucursales inactivas registradas."
              : "No hay sucursales que coincidan con los filtros."
        }
      >
        {(page) => (
          <div style={{ overflowX: "auto" }}>
          <table>
            <caption>{page.totalElements} sucursal(es)</caption>
            <thead>
              <tr>
                <th scope="col">Código</th>
                <th scope="col">Nombre</th>
                <th scope="col">Ubicación</th>
                <th scope="col">Estado</th>
                {canWrite ? <th scope="col">Acciones</th> : null}
              </tr>
            </thead>
            <tbody>
              {page.content.map((branch) => (
                <tr key={branch.id}>
                  <td>{branch.code}</td>
                  <td>{branch.name}</td>
                  <td>{branch.location ?? "—"}</td>
                  <td>
                    <span className={branch.active ? "badge badge--ok" : "badge badge--muted"}>
                      {branch.active ? "Activa" : "Inactiva"}
                    </span>
                  </td>
                  {canWrite ? (
                    <td className="row__actions">
                      <button type="button" onClick={() => setEditing(branch)}>
                        Editar
                      </button>
                      <button type="button" onClick={() => setToggling(branch)}>
                        {branch.active ? "Desactivar" : "Activar"}
                      </button>
                      <button type="button" className="button--danger" onClick={() => setDeleting(branch)}>
                        Eliminar
                      </button>
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </AsyncBoundary>

      {creating ? <BranchFormDialog onClose={() => setCreating(false)} /> : null}
      {editing ? <BranchFormDialog branch={editing} onClose={() => setEditing(undefined)} /> : null}
      {toggling ? (
        <ConfirmDialog
          title={toggling.active ? "Desactivar sucursal" : "Activar sucursal"}
          message={
            toggling.active
              ? `${toggling.code} — ${toggling.name} dejará de estar disponible para nuevas operaciones. No se elimina: sus datos se conservan.`
              : `${toggling.code} — ${toggling.name} volverá a estar disponible para nuevas operaciones.`
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
          title="Eliminar sucursal"
          message={`${deleting.code} — ${deleting.name} se eliminará permanentemente. Si tiene usuarios, inventario, compras, ventas, transferencias o rutas asociadas, la operación se rechazará: desactívala en su lugar.`}
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
