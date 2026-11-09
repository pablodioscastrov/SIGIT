import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { updateUnitOfMeasure } from "../../api/endpoints/products";
import { queryKeys } from "../../api/queryClient";
import { Field, FormErrorMessage } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { Modal } from "../../components/ui/Modal";
import type { UnitOfMeasure } from "../../types/api";

/** Edición de unidad de medida (BR-050): solo el nombre — `code` es la clave de negocio y no se edita por esta vía. */
export function UnitOfMeasureFormDialog({ unit, onClose }: { unit: UnitOfMeasure; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(unit.name);
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});

  const mutation = useMutation({
    mutationFn: () => updateUnitOfMeasure(unit.id, { name: name.trim() }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.unitsOfMeasure() });
      onClose();
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };
  const errorFor = (field: string) => localErrors[field] ?? serverErrors.fields[field];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const errors: Record<string, string> = {};
    if (!name.trim()) errors.name = "El nombre es obligatorio.";
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    mutation.mutate();
  }

  return (
    <Modal title={`Editar ${unit.code}`} onClose={onClose}>
      <form onSubmit={handleSubmit} noValidate>
        <p className="state__hint">Código {unit.code} · no es modificable.</p>
        <Field id="unit-edit-name" label="Nombre" value={name} maxLength={100} onChange={(event) => setName(event.target.value)} error={errorFor("name")} />

        <FormErrorMessage error={serverErrors.general ? mutation.error : null} />

        <div className="modal__actions">
          <button type="button" onClick={onClose} disabled={mutation.isPending}>
            Cancelar
          </button>
          <button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Guardando…" : "Guardar"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
