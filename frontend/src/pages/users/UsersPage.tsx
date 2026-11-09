import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { listBranches } from "../../api/endpoints/branches";
import { activateUser, deleteUser, listRoles, listUsers } from "../../api/endpoints/users";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { canUsers } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { AsyncBoundary } from "../../components/state/states";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { Pagination } from "../../components/ui/Pagination";
import type { User } from "../../types/api";
import { DeactivateUserDialog } from "./DeactivateUserDialog";
import { UserFormDialog } from "./UserFormDialog";

const PAGE_SIZE = 10;

/**
 * UC-14: gestión de usuarios, exclusiva de ADMIN (la ruta `/usuarios` ya lo
 * exige vía `RequireRole`). Activar/desactivar/eliminar nunca se ofrecen
 * sobre la propia fila: el backend ya lo rechaza (`NO_AUTOGESTION`, evita que
 * un ADMIN se bloquee a sí mismo), así que el botón ni se muestra. Eliminar
 * es una acción real (no reversible): el backend la rechaza con
 * `USUARIO_CON_DATOS_ASOCIADOS` si el usuario tiene historial asociado.
 */
export function UsersPage() {
  const { user } = useAuth();
  const canWrite = canUsers.write(user?.role);

  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<User | undefined>();
  const [deactivating, setDeactivating] = useState<User | undefined>();
  const [activating, setActivating] = useState<User | undefined>();
  const [deleting, setDeleting] = useState<User | undefined>();

  const query = useQuery({
    queryKey: queryKeys.users({ page, size: PAGE_SIZE }),
    queryFn: () => listUsers({ page, size: PAGE_SIZE }),
  });

  // Sucursales y roles son catálogos pequeños y de baja variación: se cargan
  // aquí tanto para resolver el nombre de sucursal en la tabla como para
  // alimentar los selectores del formulario de alta.
  const branchesQuery = useQuery({
    queryKey: queryKeys.branches({ size: 200 }),
    queryFn: () => listBranches({ size: 200 }),
    staleTime: 60_000,
  });
  const rolesQuery = useQuery({
    queryKey: queryKeys.roles(),
    queryFn: listRoles,
    staleTime: 5 * 60_000,
  });
  const branchNameById = new Map((branchesQuery.data?.content ?? []).map((branch) => [branch.id, branch.name]));

  const queryClient = useQueryClient();
  const activateMutation = useMutation({
    mutationFn: (target: User) => activateUser(target.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.users });
      setActivating(undefined);
    },
  });
  const deleteMutation = useMutation({
    mutationFn: (target: User) => deleteUser(target.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.users });
      setDeleting(undefined);
    },
  });

  return (
    <section>
      <div className="page__header">
        <h1>Usuarios</h1>
        {canWrite ? (
          <button type="button" onClick={() => setCreating(true)}>
            Nuevo usuario
          </button>
        ) : null}
      </div>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.content.length === 0}
        emptyTitle="No hay usuarios registrados."
      >
        {(result) => (
          <>
            <div style={{ overflowX: "auto" }}>
            <table>
              <caption>{result.totalElements} usuario(s)</caption>
              <thead>
                <tr>
                  <th scope="col">Nombre</th>
                  <th scope="col">Correo</th>
                  <th scope="col">Rol</th>
                  <th scope="col">Sucursal</th>
                  <th scope="col">Estado</th>
                  {canWrite ? <th scope="col">Acciones</th> : null}
                </tr>
              </thead>
              <tbody>
                {result.content.map((item) => {
                  const isSelf = item.id === user?.id;
                  return (
                    <tr key={item.id}>
                      <td>{item.name}</td>
                      <td className="cell-break">{item.email}</td>
                      <td>{item.role}</td>
                      <td>{item.branchId ? branchNameById.get(item.branchId) ?? item.branchId : "—"}</td>
                      <td>
                        <span className={item.active ? "badge badge--ok" : "badge badge--muted"}>
                          {item.active ? "Activo" : "Inactivo"}
                        </span>
                        {!item.active && item.deactivationReason ? (
                          <p className="state__hint">Motivo: {item.deactivationReason}</p>
                        ) : null}
                      </td>
                      {canWrite ? (
                        <td className="row__actions">
                          <div className="row__actions-group">
                            <button type="button" onClick={() => setEditing(item)}>
                              Editar
                            </button>
                            {isSelf ? (
                              <span className="state__hint">Tu propia cuenta</span>
                            ) : (
                              <>
                                {item.active ? (
                                  <button type="button" onClick={() => setDeactivating(item)}>
                                    Desactivar
                                  </button>
                                ) : (
                                  <button type="button" onClick={() => setActivating(item)}>
                                    Activar
                                  </button>
                                )}
                                <button type="button" className="button--danger" onClick={() => setDeleting(item)}>
                                  Eliminar
                                </button>
                              </>
                            )}
                          </div>
                        </td>
                      ) : null}
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

      {creating ? (
        <UserFormDialog roles={rolesQuery.data ?? []} branches={branchesQuery.data?.content ?? []} onClose={() => setCreating(false)} />
      ) : null}

      {editing ? (
        <UserFormDialog
          user={editing}
          roles={rolesQuery.data ?? []}
          branches={branchesQuery.data?.content ?? []}
          onClose={() => setEditing(undefined)}
        />
      ) : null}

      {deactivating ? <DeactivateUserDialog user={deactivating} onClose={() => setDeactivating(undefined)} /> : null}

      {activating ? (
        <ConfirmDialog
          title="Activar usuario"
          message={`${activating.name} volverá a poder iniciar sesión y a operar con su rol y sucursal habituales.`}
          confirmLabel="Activar"
          isPending={activateMutation.isPending}
          error={activateMutation.error}
          onConfirm={() => activateMutation.mutate(activating)}
          onCancel={() => setActivating(undefined)}
        />
      ) : null}

      {deleting ? (
        <ConfirmDialog
          title="Eliminar usuario"
          message={`${deleting.name} (${deleting.email}) se eliminará permanentemente. Si tiene movimientos, compras, ventas o transferencias asociadas, la operación se rechazará: desactívalo en su lugar.`}
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
