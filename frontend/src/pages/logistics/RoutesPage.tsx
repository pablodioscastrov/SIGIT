import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { createRoute, listRoutes, reclassifyRoute } from "../../api/endpoints/routes";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { canLogistics } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { FormErrorMessage } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { AsyncBoundary } from "../../components/state/states";
import type { Route, RouteClassification } from "../../types/api";
import { ROUTE_CLASSIFICATION_LABELS } from "./routeLabels";

const CLASSIFICATIONS: RouteClassification[] = ["PRIORITY", "COST", "TIME"];

/**
 * Catálogo de rutas clasificadas (RF-028). Lectura abierta a cualquier rol;
 * clasificar o reclasificar es `MANAGER`/`ADMIN`. El par origen-destino es la
 * identidad de la ruta y no se puede editar (`UpdateRouteRequest` solo trae
 * `classification`) — por eso reclasificar es un `<select>` en línea, sin
 * reabrir origen/destino.
 */
export function RoutesPage() {
  const { user } = useAuth();
  const canWrite = canLogistics.writeRoutes(user?.role);
  const queryClient = useQueryClient();

  const [branchId, setBranchId] = useState("");
  const [classification, setClassification] = useState<RouteClassification | "">("");
  const [editing, setEditing] = useState<Record<string, RouteClassification>>({});

  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const branchesById = new Map((branchesQuery.data?.content ?? []).map((branch) => [branch.id, branch]));

  const params = { branchId: branchId || undefined, classification: classification || undefined, size: 100 };
  const query = useQuery({ queryKey: queryKeys.routes(params), queryFn: () => listRoutes(params) });

  const reclassifyMutation = useMutation({
    mutationFn: ({ id, value }: { id: string; value: RouteClassification }) => reclassifyRoute(id, { classification: value }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: queryPrefixes.routes }),
  });

  const [originBranchId, setOriginBranchId] = useState("");
  const [destinationBranchId, setDestinationBranchId] = useState("");
  const [newClassification, setNewClassification] = useState<RouteClassification>("PRIORITY");
  const [localError, setLocalError] = useState<string | undefined>();

  const createMutation = useMutation({
    mutationFn: () =>
      createRoute({ originBranchId: Number(originBranchId), destinationBranchId: Number(destinationBranchId), classification: newClassification }),
    onSuccess: () => {
      setOriginBranchId("");
      setDestinationBranchId("");
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.routes });
    },
  });

  const serverErrors = createMutation.error ? toFormErrors(createMutation.error) : { fields: {} as Record<string, string> };

  function handleCreate(event: FormEvent) {
    event.preventDefault();
    setLocalError(undefined);
    if (!originBranchId || !destinationBranchId) {
      setLocalError("Selecciona origen y destino.");
      return;
    }
    if (originBranchId === destinationBranchId) {
      setLocalError("El origen y el destino deben ser sucursales distintas.");
      return;
    }
    createMutation.mutate();
  }

  return (
    <section>
      <div className="page__header">
        <h1>Rutas</h1>
        <Link to="/logistica/cumplimiento">Cumplimiento logístico</Link>
      </div>

      <form className="filters" onSubmit={(event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="routes-branch">Sucursal (origen o destino)</label>
          <select id="routes-branch" value={branchId} onChange={(event) => setBranchId(event.target.value)}>
            <option value="">Todas</option>
            {(branchesQuery.data?.content ?? []).map((branch) => (
              <option key={branch.id} value={branch.id}>
                {branch.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="routes-classification">Clasificación</label>
          <select id="routes-classification" value={classification} onChange={(event) => setClassification(event.target.value as RouteClassification)}>
            <option value="">Todas</option>
            {CLASSIFICATIONS.map((value) => (
              <option key={value} value={value}>
                {ROUTE_CLASSIFICATION_LABELS[value]}
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
        emptyTitle="No hay rutas clasificadas que coincidan con los filtros."
      >
        {(result) => (
          <div style={{ overflowX: "auto" }}>
          <table>
            <caption>{result.totalElements} ruta(s)</caption>
            <thead>
              <tr>
                <th scope="col">Origen</th>
                <th scope="col">Destino</th>
                <th scope="col">Clasificación</th>
                {canWrite ? <th scope="col">Acciones</th> : null}
              </tr>
            </thead>
            <tbody>
              {result.content.map((route: Route) => (
                <tr key={route.id}>
                  <td>{branchesById.get(route.originBranchId)?.name ?? route.originBranchId}</td>
                  <td>{branchesById.get(route.destinationBranchId)?.name ?? route.destinationBranchId}</td>
                  <td>{ROUTE_CLASSIFICATION_LABELS[route.classification]}</td>
                  {canWrite ? (
                    <td className="row__actions">
                      <select
                        aria-label={`Reclasificar ${route.originBranchId} a ${route.destinationBranchId}`}
                        value={editing[route.id] ?? route.classification}
                        onChange={(event) => setEditing((current) => ({ ...current, [route.id]: event.target.value as RouteClassification }))}
                      >
                        {CLASSIFICATIONS.map((value) => (
                          <option key={value} value={value}>
                            {ROUTE_CLASSIFICATION_LABELS[value]}
                          </option>
                        ))}
                      </select>
                      <button
                        type="button"
                        disabled={(editing[route.id] ?? route.classification) === route.classification}
                        onClick={() => reclassifyMutation.mutate({ id: route.id, value: editing[route.id] ?? route.classification })}
                      >
                        Guardar
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

      <FormErrorMessage error={reclassifyMutation.error} />

      {canWrite ? (
        <form onSubmit={handleCreate} noValidate className="panel">
          <h2>Nueva ruta</h2>
          <div className="filters">
            <div className="field">
              <label htmlFor="new-route-origin">Origen</label>
              <select id="new-route-origin" value={originBranchId} onChange={(event) => setOriginBranchId(event.target.value)}>
                <option value="">Selecciona…</option>
                {(branchesQuery.data?.content ?? []).map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="new-route-destination">Destino</label>
              <select id="new-route-destination" value={destinationBranchId} onChange={(event) => setDestinationBranchId(event.target.value)}>
                <option value="">Selecciona…</option>
                {(branchesQuery.data?.content ?? []).map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="new-route-classification">Clasificación</label>
              <select id="new-route-classification" value={newClassification} onChange={(event) => setNewClassification(event.target.value as RouteClassification)}>
                {CLASSIFICATIONS.map((value) => (
                  <option key={value} value={value}>
                    {ROUTE_CLASSIFICATION_LABELS[value]}
                  </option>
                ))}
              </select>
            </div>
          </div>
          {localError ? <p role="alert" className="field__error">{localError}</p> : null}
          <FormErrorMessage error={serverErrors.general ? createMutation.error : null} />
          <button type="submit" disabled={createMutation.isPending}>
            {createMutation.isPending ? "Creando…" : "Crear ruta"}
          </button>
        </form>
      ) : null}
    </section>
  );
}
