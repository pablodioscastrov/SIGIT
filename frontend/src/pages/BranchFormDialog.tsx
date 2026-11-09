import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { createBranch, updateBranch } from "../api/endpoints/branches";
import { queryPrefixes } from "../api/queryClient";
import { Field, FormErrorMessage } from "../components/form/Field";
import { toFormErrors } from "../components/form/formErrors";
import { Modal } from "../components/ui/Modal";
import type { Branch } from "../types/api";

/**
 * Alta y edición de sucursal en un mismo diálogo (UC-15): `code` es la clave
 * de negocio y no se edita después (docs/API_DESIGN.md, sección 7.3), por
 * eso en edición se muestra como dato fijo en vez de un campo editable.
 */
export function BranchFormDialog({ branch, onClose }: { branch?: Branch; onClose: () => void }) {
  const isEdit = branch !== undefined;
  const queryClient = useQueryClient();
  const [code, setCode] = useState(branch?.code ?? "");
  const [name, setName] = useState(branch?.name ?? "");
  const [location, setLocation] = useState(branch?.location ?? "");
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});

  const mutation = useMutation({
    mutationFn: () =>
      isEdit
        ? updateBranch(branch.id, { name: name.trim(), location: location.trim() || null })
        : createBranch({ code: code.trim(), name: name.trim(), location: location.trim() || null }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.branches });
      onClose();
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };
  const errorFor = (field: string) => localErrors[field] ?? serverErrors.fields[field];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    // Validación solo de forma; el código duplicado lo decide el backend.
    const errors: Record<string, string> = {};
    if (!isEdit && !code.trim()) errors.code = "El código es obligatorio.";
    if (!name.trim()) errors.name = "El nombre es obligatorio.";
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    mutation.mutate();
  }

  return (
    <Modal title={isEdit ? `Editar ${branch.code}` : "Nueva sucursal"} onClose={onClose}>
      <form onSubmit={handleSubmit} noValidate>
        {isEdit ? (
          <p className="state__hint">Código {branch.code} · no es modificable.</p>
        ) : (
          <Field id="branch-code" label="Código" value={code} maxLength={20} onChange={(event) => setCode(event.target.value)} error={errorFor("code")} />
        )}

        <Field id="branch-name" label="Nombre" value={name} maxLength={150} onChange={(event) => setName(event.target.value)} error={errorFor("name")} />
        <Field
          id="branch-location"
          label="Ubicación"
          value={location}
          maxLength={255}
          onChange={(event) => setLocation(event.target.value)}
          error={errorFor("location")}
        />

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
