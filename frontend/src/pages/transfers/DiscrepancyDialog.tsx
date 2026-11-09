import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { applyDiscrepancyTreatment } from "../../api/endpoints/transfers";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { FormErrorMessage, TextAreaField } from "../../components/form/Field";
import { Modal } from "../../components/ui/Modal";
import type { DiscrepancyTreatment, Transfer, TransferItem } from "../../types/api";
import { isStateConflict } from "./conflicts";
import { DISCREPANCY_TREATMENT_HINTS, DISCREPANCY_TREATMENT_LABELS } from "./labels";
import { productLabel, useProductIndex } from "../products/useCatalog";

const TREATMENTS: DiscrepancyTreatment[] = ["REENVIO", "AJUSTE", "RECLAMACION"];

/**
 * Tratamiento del faltante de una línea (flujo F2, BR-009). Si el tratamiento
 * es `REENVIO`, el backend crea la transferencia de reposición en la misma
 * transacción y devuelve su id — se enlaza aquí en vez de obligar a buscarla
 * en el listado. Si era el último faltante sin tratar, la transferencia
 * actual cierra sola; la respuesta lo confirma (`transferStatus`).
 */
export function DiscrepancyDialog({
  transfer,
  item,
  onClose,
  onConflict,
}: {
  transfer: Transfer;
  item: TransferItem;
  onClose: () => void;
  onConflict: () => void;
}) {
  const queryClient = useQueryClient();
  const { byId: productsById } = useProductIndex();
  const [treatment, setTreatment] = useState<DiscrepancyTreatment>("REENVIO");
  const [notes, setNotes] = useState("");
  const [result, setResult] = useState<{ followUpTransferId: string | null; transferStatus: string } | null>(null);

  const mutation = useMutation({
    mutationFn: () => applyDiscrepancyTreatment(transfer.id, item.id, { treatment, notes: notes.trim() || null }),
    onSuccess: (response) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.transfer(transfer.id) });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.transfers });
      setResult({ followUpTransferId: response.followUpTransferId, transferStatus: response.transferStatus });
    },
    onError: (error) => {
      if (isStateConflict(error)) onConflict();
    },
  });

  if (result) {
    return (
      <Modal title="Tratamiento registrado" onClose={onClose}>
        <p>
          Se registró <strong>{DISCREPANCY_TREATMENT_LABELS[treatment]}</strong> para el faltante de{" "}
          {productLabel(productsById.get(item.productId), item.productId)}.
        </p>
        {result.followUpTransferId ? (
          <p>
            Se creó la transferencia de reposición{" "}
            <Link to={`/transferencias/${result.followUpTransferId}`}>#{result.followUpTransferId}</Link>.
          </p>
        ) : null}
        {result.transferStatus === "CLOSED" ? <p className="state__hint">Era el último faltante pendiente: la transferencia quedó Cerrada.</p> : null}
        <div className="modal__actions">
          <button type="button" onClick={onClose}>
            Cerrar
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal title={`Tratar faltante — ${productLabel(productsById.get(item.productId), item.productId)}`} onClose={onClose}>
      <p className="state__hint">
        Faltante: {item.quantityMissing} (enviado {item.quantityShipped}, recibido {item.quantityReceived}).
      </p>

      <fieldset>
        <legend>Tratamiento</legend>
        {TREATMENTS.map((value) => (
          <label key={value} className="radio-option">
            <input
              type="radio"
              name="discrepancy-treatment"
              value={value}
              checked={treatment === value}
              onChange={() => setTreatment(value)}
            />
            {DISCREPANCY_TREATMENT_LABELS[value]}
            <span className="state__hint"> — {DISCREPANCY_TREATMENT_HINTS[value]}</span>
          </label>
        ))}
      </fieldset>

      <TextAreaField id="discrepancy-notes" label="Notas (opcional)" value={notes} rows={3} onChange={(event) => setNotes(event.target.value)} />

      <FormErrorMessage error={mutation.error} />

      <div className="modal__actions">
        <button type="button" onClick={onClose} disabled={mutation.isPending}>
          Cancelar
        </button>
        <button type="button" onClick={() => mutation.mutate()} disabled={mutation.isPending}>
          {mutation.isPending ? "Registrando…" : "Registrar tratamiento"}
        </button>
      </div>
    </Modal>
  );
}
