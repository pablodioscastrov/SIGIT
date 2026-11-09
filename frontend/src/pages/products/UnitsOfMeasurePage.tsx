import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { createUnitOfMeasure } from "../../api/endpoints/products";
import { queryKeys } from "../../api/queryClient";
import { can } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { Field, FormErrorMessage } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { AsyncBoundary } from "../../components/state/states";
import type { UnitOfMeasure } from "../../types/api";
import { UnitOfMeasureFormDialog } from "./UnitOfMeasureFormDialog";
import { useUnitsOfMeasure } from "./useCatalog";

/**
 * Catálogo global de unidades de medida. La lectura es abierta a cualquier
 * usuario autenticado; el alta y la edición del nombre (BR-050) son
 * exclusivas de ADMIN —más estricto que el resto del módulo de productos—
 * porque una unidad mal definida afecta a las conversiones de todos los
 * productos que la usen. El código es la clave de negocio y no se edita.
 */
export function UnitsOfMeasurePage() {
  const { user } = useAuth();
  const canCreate = can.createUnitOfMeasure(user?.role);
  const query = useUnitsOfMeasure();
  const queryClient = useQueryClient();

  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const [editing, setEditing] = useState<UnitOfMeasure | undefined>();

  const mutation = useMutation({
    mutationFn: () => createUnitOfMeasure({ code: code.trim(), name: name.trim() }),
    onSuccess: () => {
      setCode("");
      setName("");
      void queryClient.invalidateQueries({ queryKey: queryKeys.unitsOfMeasure() });
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };
  const errorFor = (field: string) => localErrors[field] ?? serverErrors.fields[field];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const errors: Record<string, string> = {};
    if (!code.trim()) errors.code = "El código es obligatorio.";
    if (!name.trim()) errors.name = "El nombre es obligatorio.";
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    mutation.mutate();
  }

  return (
    <section>
      <div className="page__header">
        <h1>Unidades de medida</h1>
        <Link to="/productos">Volver a productos</Link>
      </div>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(rows) => rows.length === 0}
        emptyTitle="Todavía no hay unidades de medida registradas."
      >
        {(rows) => (
          <div style={{ overflowX: "auto" }}>
          <table>
            <caption>{rows.length} unidad(es)</caption>
            <thead>
              <tr>
                <th scope="col">Código</th>
                <th scope="col">Nombre</th>
                {canCreate ? <th scope="col">Acciones</th> : null}
              </tr>
            </thead>
            <tbody>
              {rows.map((unit) => (
                <tr key={unit.id}>
                  <td>{unit.code}</td>
                  <td>{unit.name}</td>
                  {canCreate ? (
                    <td className="row__actions">
                      <button type="button" onClick={() => setEditing(unit)}>
                        Editar
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

      {canCreate ? (
        <form onSubmit={handleSubmit} noValidate className="panel">
          <h2>Nueva unidad de medida</h2>
          <Field
            id="unit-code"
            label="Código"
            value={code}
            maxLength={10}
            onChange={(event) => setCode(event.target.value)}
            error={errorFor("code")}
          />
          <Field
            id="unit-name"
            label="Nombre"
            value={name}
            maxLength={100}
            onChange={(event) => setName(event.target.value)}
            error={errorFor("name")}
          />
          <FormErrorMessage error={serverErrors.general ? mutation.error : null} />
          <button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Creando…" : "Crear unidad"}
          </button>
        </form>
      ) : (
        <p className="state__hint">Solo un administrador puede dar de alta unidades de medida.</p>
      )}

      {editing ? <UnitOfMeasureFormDialog unit={editing} onClose={() => setEditing(undefined)} /> : null}
    </section>
  );
}
