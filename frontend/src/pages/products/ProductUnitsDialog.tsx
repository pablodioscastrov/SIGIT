import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { addProductUnit, listProductUnits, updateProductUnitFactor } from "../../api/endpoints/products";
import { queryKeys } from "../../api/queryClient";
import { Field, FormErrorMessage, SelectField } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { AsyncBoundary } from "../../components/state/states";
import { Modal } from "../../components/ui/Modal";
import type { Product, UnitOfMeasure } from "../../types/api";

/**
 * Unidades de un producto y su factor de conversión hacia la unidad base
 * (BR-011). El factor de la unidad base es 1 y es inmutable: el backend
 * responde 422 `UNIDAD_BASE_INMUTABLE`, y aquí simplemente no se ofrece editarlo.
 */
export function ProductUnitsDialog({
  product,
  units,
  canWrite,
  onClose,
}: {
  product: Product;
  units: UnitOfMeasure[];
  canWrite: boolean;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: queryKeys.productUnits(product.id),
    queryFn: () => listProductUnits(product.id),
  });

  const [unitId, setUnitId] = useState("");
  const [factor, setFactor] = useState("");
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const [editing, setEditing] = useState<{ unitOfMeasureId: string; factor: string } | null>(null);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: queryKeys.productUnits(product.id) });

  const addMutation = useMutation({
    mutationFn: () =>
      addProductUnit(product.id, { unitOfMeasureId: Number(unitId), conversionFactorToBase: Number(factor) }),
    onSuccess: () => {
      setUnitId("");
      setFactor("");
      void invalidate();
    },
  });

  const editMutation = useMutation({
    mutationFn: (payload: { unitOfMeasureId: string; factor: string }) =>
      updateProductUnitFactor(product.id, payload.unitOfMeasureId, Number(payload.factor)),
    onSuccess: () => {
      setEditing(null);
      void invalidate();
    },
  });

  const addErrors = addMutation.error ? toFormErrors(addMutation.error) : { fields: {} as Record<string, string> };
  const errorFor = (field: string) => localErrors[field] ?? addErrors.fields[field];

  function handleAdd(event: FormEvent) {
    event.preventDefault();
    const errors: Record<string, string> = {};
    if (!unitId) errors.unitOfMeasureId = "Selecciona una unidad.";
    if (!factor.trim()) errors.conversionFactorToBase = "El factor es obligatorio.";
    else if (!(Number(factor) > 0)) errors.conversionFactorToBase = "El factor debe ser mayor que cero.";
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    addMutation.mutate();
  }

  const associated = new Set((query.data ?? []).map((productUnit) => productUnit.unitOfMeasureId));
  const available = units.filter((unit) => !associated.has(unit.id));

  return (
    <Modal title={`Unidades de ${product.sku}`} onClose={onClose}>
      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(rows) => rows.length === 0}
        emptyTitle="Este producto no tiene unidades registradas."
      >
        {(rows) => (
          <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                <th scope="col">Unidad</th>
                <th scope="col">Factor a la base</th>
                <th scope="col">Acciones</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.unitOfMeasureId}>
                  <td>
                    {row.unitCode} — {row.unitName}
                    {row.baseUnit ? <span className="badge"> base</span> : null}
                  </td>
                  <td>
                    {editing?.unitOfMeasureId === row.unitOfMeasureId ? (
                      <input
                        aria-label={`Factor de ${row.unitCode}`}
                        value={editing.factor}
                        onChange={(event) => setEditing({ ...editing, factor: event.target.value })}
                      />
                    ) : (
                      row.conversionFactorToBase
                    )}
                  </td>
                  <td>
                    {!canWrite || row.baseUnit ? (
                      <span className="state__hint">—</span>
                    ) : editing?.unitOfMeasureId === row.unitOfMeasureId ? (
                      <>
                        <button type="button" onClick={() => editMutation.mutate(editing)} disabled={editMutation.isPending}>
                          Guardar
                        </button>
                        <button type="button" onClick={() => setEditing(null)}>
                          Cancelar
                        </button>
                      </>
                    ) : (
                      <button
                        type="button"
                        onClick={() =>
                          setEditing({ unitOfMeasureId: row.unitOfMeasureId, factor: String(row.conversionFactorToBase) })
                        }
                      >
                        Editar factor
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </AsyncBoundary>

      <FormErrorMessage error={editMutation.error} />

      {canWrite ? (
        <form onSubmit={handleAdd} noValidate>
          <h3>Agregar unidad</h3>
          <SelectField
            id="product-unit-id"
            label="Unidad de medida"
            value={unitId}
            onChange={(event) => setUnitId(event.target.value)}
            error={errorFor("unitOfMeasureId")}
          >
            <option value="">Selecciona…</option>
            {available.map((unit) => (
              <option key={unit.id} value={unit.id}>
                {unit.code} — {unit.name}
              </option>
            ))}
          </SelectField>
          <Field
            id="product-unit-factor"
            label="Factor de conversión a la unidad base"
            inputMode="decimal"
            value={factor}
            onChange={(event) => setFactor(event.target.value)}
            error={errorFor("conversionFactorToBase")}
          />
          <FormErrorMessage error={addErrors.general ? addMutation.error : null} />
          <button type="submit" disabled={addMutation.isPending}>
            {addMutation.isPending ? "Agregando…" : "Agregar unidad"}
          </button>
        </form>
      ) : null}

      <div className="modal__actions">
        <button type="button" onClick={onClose}>
          Cerrar
        </button>
      </div>
    </Modal>
  );
}
