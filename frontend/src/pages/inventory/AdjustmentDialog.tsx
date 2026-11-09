import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { listProductUnits } from "../../api/endpoints/products";
import { createAdjustment } from "../../api/endpoints/inventory";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { Field, FormErrorMessage, SelectField, TextAreaField } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { Modal } from "../../components/ui/Modal";
import { ADJUSTMENT_REASONS } from "../../types/api";
import type { InventoryRow, MovementDirection, MovementReason } from "../../types/api";

const REASON_LABELS: Record<MovementReason, string> = {
  COMPRA: "Compra",
  DEVOLUCION: "Devolución",
  AJUSTE_INGRESO: "Ajuste de ingreso",
  VENTA: "Venta",
  MERMA: "Merma",
  AJUSTE_RETIRO: "Ajuste de retiro",
  TRANSFERENCIA_SALIDA: "Transferencia (salida)",
  TRANSFERENCIA_ENTRADA: "Transferencia (entrada)",
};

/**
 * Ajuste manual de inventario (flujo G). Modifica stock real, así que exige
 * confirmación explícita del resumen antes de enviarse y un motivo escrito
 * obligatorio, que queda en el `InventoryMovement` como traza de quién ajustó
 * qué y por qué.
 *
 * <p>La interfaz no decide si el ajuste es posible: si el retiro deja el stock
 * en negativo, el backend responde 422 `STOCK_INSUFICIENTE` y ese error se
 * muestra tal cual junto al campo de cantidad.
 */
export function AdjustmentDialog({
  row,
  productLabel,
  branchLabel,
  onClose,
}: {
  row: InventoryRow;
  productLabel: string;
  branchLabel: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [direction, setDirection] = useState<MovementDirection>("INGRESO");
  const [reason, setReason] = useState<MovementReason | "">("");
  const [quantity, setQuantity] = useState("");
  const [unitOfMeasureId, setUnitOfMeasureId] = useState("");
  const [notes, setNotes] = useState("");
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const [confirming, setConfirming] = useState(false);

  const unitsQuery = useQuery({
    queryKey: queryKeys.productUnits(row.productId),
    queryFn: () => listProductUnits(row.productId),
  });

  const mutation = useMutation({
    mutationFn: () =>
      createAdjustment({
        branchId: Number(row.branchId),
        productId: Number(row.productId),
        unitOfMeasureId: unitOfMeasureId ? Number(unitOfMeasureId) : null,
        direction,
        reason: reason === "" ? null : reason,
        quantity: Number(quantity),
        notes: notes.trim(),
      }),
    onSuccess: () => {
      // El ajuste cambia stock y crea un movimiento: se revalidan ambas vistas
      // contra la API en vez de actualizar la caché a mano.
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventory });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventoryMovements });
      onClose();
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };
  const errorFor = (field: string) => localErrors[field] ?? serverErrors.fields[field];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const errors: Record<string, string> = {};
    if (!quantity.trim()) errors.quantity = "La cantidad es obligatoria.";
    else if (!(Number(quantity) > 0)) errors.quantity = "La cantidad debe ser mayor que cero.";
    if (!notes.trim()) errors.notes = "Explica el motivo del ajuste: queda registrado en el historial.";
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    setConfirming(true);
  }

  /**
   * Volver al formulario descarta el error del intento anterior: dejarlo
   * visible sobre un resumen ya corregido haría parecer que el nuevo intento
   * también falló.
   */
  function backToForm() {
    mutation.reset();
    setConfirming(false);
  }

  if (confirming) {
    const verb = direction === "INGRESO" ? "sumará" : "restará";
    const unitCode = unitsQuery.data?.find((unit) => unit.unitOfMeasureId === unitOfMeasureId)?.unitCode;
    return (
      <Modal title="Confirmar ajuste de inventario" onClose={backToForm}>
        <p>
          Se {verb} <strong>{quantity}</strong> {unitCode ?? "(unidad base)"} de <strong>{productLabel}</strong> en{" "}
          <strong>{branchLabel}</strong>. El movimiento queda registrado a tu nombre y no puede editarse ni borrarse.
        </p>
        <FormErrorMessage error={mutation.error} />
        <div className="modal__actions">
          <button type="button" onClick={backToForm} disabled={mutation.isPending}>
            Volver
          </button>
          <button type="button" className="button--danger" onClick={() => mutation.mutate()} disabled={mutation.isPending}>
            {mutation.isPending ? "Registrando…" : "Registrar ajuste"}
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal title="Ajuste manual de inventario" onClose={onClose}>
      <form onSubmit={handleSubmit} noValidate>
        <p className="state__hint">
          {productLabel} · {branchLabel} · stock actual {row.quantityOnHand}
        </p>

        <SelectField
          id="adjustment-direction"
          label="Tipo de movimiento"
          value={direction}
          onChange={(event) => {
            setDirection(event.target.value as MovementDirection);
            setReason("");
          }}
        >
          <option value="INGRESO">Ingreso</option>
          <option value="RETIRO">Retiro</option>
        </SelectField>

        <SelectField
          id="adjustment-reason"
          label="Motivo"
          value={reason}
          onChange={(event) => setReason(event.target.value as MovementReason)}
          error={errorFor("reason")}
        >
          <option value="">Predeterminado según el tipo</option>
          {ADJUSTMENT_REASONS[direction].map((value) => (
            <option key={value} value={value}>
              {REASON_LABELS[value]}
            </option>
          ))}
        </SelectField>

        <Field
          id="adjustment-quantity"
          label="Cantidad"
          inputMode="decimal"
          value={quantity}
          onChange={(event) => setQuantity(event.target.value)}
          error={errorFor("quantity")}
        />

        <SelectField
          id="adjustment-unit"
          label="Unidad"
          value={unitOfMeasureId}
          onChange={(event) => setUnitOfMeasureId(event.target.value)}
          error={errorFor("unitOfMeasureId")}
        >
          <option value="">Unidad base del producto</option>
          {(unitsQuery.data ?? []).map((unit) => (
            <option key={unit.unitOfMeasureId} value={unit.unitOfMeasureId}>
              {unit.unitCode} — {unit.unitName}
            </option>
          ))}
        </SelectField>

        <TextAreaField
          id="adjustment-notes"
          label="Motivo del ajuste (obligatorio)"
          value={notes}
          rows={3}
          onChange={(event) => setNotes(event.target.value)}
          error={errorFor("notes")}
        />

        <FormErrorMessage error={serverErrors.general ? mutation.error : null} />

        <div className="modal__actions">
          <button type="button" onClick={onClose}>
            Cancelar
          </button>
          <button type="submit">Continuar</button>
        </div>
      </form>
    </Modal>
  );
}
