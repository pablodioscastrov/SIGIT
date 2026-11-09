import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { approveTransfer } from "../../api/endpoints/transfers";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { FormErrorMessage } from "../../components/form/Field";
import { Modal } from "../../components/ui/Modal";
import type { ApproveTransferItemRequest, Transfer } from "../../types/api";
import { isStateConflict } from "./conflicts";
import { productLabel, useProductIndex } from "../products/useCatalog";

/**
 * Aprobación (flujo C2, BR-005). La cantidad aprobada no puede superar la
 * solicitada — un límite estructural de la propia línea, no un cálculo de
 * inventario, así que se ofrece como tope del campo. La disponibilidad real
 * de stock la valida el backend al confirmar (`422
 * STOCK_INSUFICIENTE_PARA_TRANSFERENCIA`); esta pantalla no la precalcula.
 */
export function ApproveDialog({
  transfer,
  onClose,
  onConflict,
}: {
  transfer: Transfer;
  onClose: () => void;
  onConflict: () => void;
}) {
  const queryClient = useQueryClient();
  const { byId: productsById } = useProductIndex();
  const [quantities, setQuantities] = useState<Record<string, string>>(() =>
    Object.fromEntries(transfer.items.map((item) => [item.id, String(item.quantityRequested)])),
  );
  const [localError, setLocalError] = useState<string | undefined>();

  const mutation = useMutation({
    mutationFn: (items: ApproveTransferItemRequest[]) => approveTransfer(transfer.id, { items }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.transfer(transfer.id) });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.transfers });
      onClose();
    },
    onError: (error) => {
      if (isStateConflict(error)) onConflict();
    },
  });

  function handleSubmit() {
    setLocalError(undefined);
    const items: ApproveTransferItemRequest[] = [];
    for (const item of transfer.items) {
      const value = quantities[item.id]?.trim();
      if (!value || !(Number(value) > 0)) {
        setLocalError("Cada línea necesita una cantidad aprobada mayor que cero.");
        return;
      }
      if (Number(value) > item.quantityRequested) {
        setLocalError("Ninguna línea puede aprobarse por más de lo solicitado.");
        return;
      }
      items.push({ transferItemId: Number(item.id), quantityApproved: Number(value) });
    }
    mutation.mutate(items);
  }

  return (
    <Modal title={`Aprobar ${transfer.transferNumber}`} onClose={onClose}>
      <div style={{ overflowX: "auto" }}>
      <table>
        <thead>
          <tr>
            <th scope="col">Producto</th>
            <th scope="col">Solicitado</th>
            <th scope="col">Cantidad a aprobar</th>
          </tr>
        </thead>
        <tbody>
          {transfer.items.map((item) => (
            <tr key={item.id}>
              <td>{productLabel(productsById.get(item.productId), item.productId)}</td>
              <td>{item.quantityRequested}</td>
              <td>
                <input
                  aria-label={`Cantidad a aprobar de ${productLabel(productsById.get(item.productId), item.productId)}`}
                  inputMode="decimal"
                  value={quantities[item.id] ?? ""}
                  onChange={(event) => setQuantities((current) => ({ ...current, [item.id]: event.target.value }))}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>

      {localError ? <p role="alert" className="field__error">{localError}</p> : null}
      <FormErrorMessage error={mutation.error} />

      <div className="modal__actions">
        <button type="button" onClick={onClose} disabled={mutation.isPending}>
          Cancelar
        </button>
        <button type="button" onClick={handleSubmit} disabled={mutation.isPending}>
          {mutation.isPending ? "Aprobando…" : "Aprobar"}
        </button>
      </div>
    </Modal>
  );
}
