import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { createSupplier, updateSupplier } from "../../api/endpoints/suppliers";
import { queryPrefixes } from "../../api/queryClient";
import { Field, FormErrorMessage } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { Modal } from "../../components/ui/Modal";
import type { Supplier } from "../../types/api";

/**
 * Alta y edición de proveedor en un mismo diálogo (BR-049): `taxId` es la
 * clave de negocio y no se edita después (misma convención que
 * `Branch.code`/`Product.sku`), por eso en edición se muestra como dato fijo
 * en vez de un campo editable.
 */
export function SupplierFormDialog({ supplier, onClose }: { supplier?: Supplier; onClose: () => void }) {
  const isEdit = supplier !== undefined;
  const queryClient = useQueryClient();
  const [name, setName] = useState(supplier?.name ?? "");
  const [taxId, setTaxId] = useState(supplier?.taxId ?? "");
  const [contactName, setContactName] = useState(supplier?.contactName ?? "");
  const [phone, setPhone] = useState(supplier?.phone ?? "");
  const [email, setEmail] = useState(supplier?.email ?? "");
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});

  const mutation = useMutation({
    mutationFn: () =>
      isEdit
        ? updateSupplier(supplier.id, {
            name: name.trim(),
            contactName: contactName.trim() || null,
            phone: phone.trim() || null,
            email: email.trim() || null,
          })
        : createSupplier({
            name: name.trim(),
            taxId: taxId.trim(),
            contactName: contactName.trim() || null,
            phone: phone.trim() || null,
            email: email.trim() || null,
          }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.suppliers });
      onClose();
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };
  const errorFor = (field: string) => localErrors[field] ?? serverErrors.fields[field];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    // Validación solo de forma; la identificación fiscal duplicada la decide el backend.
    const errors: Record<string, string> = {};
    if (!isEdit && !taxId.trim()) errors.taxId = "La identificación fiscal es obligatoria.";
    if (!name.trim()) errors.name = "El nombre es obligatorio.";
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    mutation.mutate();
  }

  return (
    <Modal title={isEdit ? `Editar ${supplier.name}` : "Nuevo proveedor"} onClose={onClose}>
      <form onSubmit={handleSubmit} noValidate>
        {isEdit ? (
          <p className="state__hint">Identificación fiscal {supplier.taxId} · no es modificable.</p>
        ) : (
          <Field
            id="supplier-tax-id"
            label="Identificación fiscal"
            value={taxId}
            maxLength={50}
            onChange={(event) => setTaxId(event.target.value)}
            error={errorFor("taxId")}
          />
        )}

        <Field id="supplier-name" label="Razón social" value={name} maxLength={150} onChange={(event) => setName(event.target.value)} error={errorFor("name")} />
        <Field
          id="supplier-contact-name"
          label="Contacto"
          value={contactName}
          maxLength={150}
          onChange={(event) => setContactName(event.target.value)}
          error={errorFor("contactName")}
        />
        <Field
          id="supplier-phone"
          label="Teléfono"
          value={phone}
          maxLength={30}
          onChange={(event) => setPhone(event.target.value)}
          error={errorFor("phone")}
        />
        <Field
          id="supplier-email"
          label="Correo"
          type="email"
          value={email}
          maxLength={255}
          onChange={(event) => setEmail(event.target.value)}
          error={errorFor("email")}
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
