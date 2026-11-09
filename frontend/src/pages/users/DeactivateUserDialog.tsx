import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { deactivateUser } from "../../api/endpoints/users";
import { queryPrefixes } from "../../api/queryClient";
import { FormErrorMessage, TextAreaField } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { Modal } from "../../components/ui/Modal";
import type { User } from "../../types/api";

/**
 * Desactivar exige explicar el motivo (UC-14): queda visible en el listado
 * mientras el usuario siga desactivado, y se limpia al reactivarlo.
 */
export function DeactivateUserDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});

  const mutation = useMutation({
    mutationFn: () => deactivateUser(user.id, reason.trim()),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.users });
      onClose();
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };
  const errorFor = (field: string) => localErrors[field] ?? serverErrors.fields[field];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const errors: Record<string, string> = {};
    if (!reason.trim()) errors.reason = "El motivo es obligatorio.";
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    mutation.mutate();
  }

  return (
    <Modal title={`Desactivar a ${user.name}`} onClose={onClose}>
      <form onSubmit={handleSubmit} noValidate>
        <TextAreaField
          id="deactivate-reason"
          label="Motivo"
          value={reason}
          maxLength={500}
          rows={3}
          onChange={(event) => setReason(event.target.value)}
          error={errorFor("reason")}
        />

        <FormErrorMessage error={serverErrors.general ? mutation.error : null} />

        <div className="modal__actions">
          <button type="button" onClick={onClose} disabled={mutation.isPending}>
            Cancelar
          </button>
          <button type="submit" className="button--danger" disabled={mutation.isPending}>
            {mutation.isPending ? "Guardando…" : "Desactivar"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
