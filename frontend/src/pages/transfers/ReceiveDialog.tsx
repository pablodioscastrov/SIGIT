import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { receiveTransfer } from "../../api/endpoints/transfers";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { FormErrorMessage } from "../../components/form/Field";
import { Modal } from "../../components/ui/Modal";
import type { ReceiveTransferItemRequest, Transfer } from "../../types/api";
import { isStateConflict } from "./conflicts";
import { productLabel, useProductIndex } from "../products/useCatalog";

/**
 * Recepción (flujo E/F1). A diferencia del despacho, admite un subconjunto de
 * líneas por llamada — el conteo físico puede hacerse línea por línea en
 * momentos distintos — así que solo se ofrece un campo para las líneas que
 * todavía no tienen `quantityReceived`; las ya recibidas se muestran de solo
 * lectura. Cero es una recepción válida ("no llegó nada de esta línea").
 */
export function ReceiveDialog({
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
  const pendingItems = transfer.items.filter((item) => item.quantityReceived === null);
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [localError, setLocalError] = useState<string | undefined>();
  const [confirming, setConfirming] = useState(false);

  const mutation = useMutation({
    mutationFn: (items: ReceiveTransferItemRequest[]) => receiveTransfer(transfer.id, { items }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.transfer(transfer.id) });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.transfers });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventory });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventoryMovements });
      onClose();
    },
    onError: (error) => {
      if (isStateConflict(error)) onConflict();
    },
  });

  function buildItems(): ReceiveTransferItemRequest[] | null {
    setLocalError(undefined);
    const items: ReceiveTransferItemRequest[] = [];
    for (const item of pendingItems) {
      const raw = quantities[item.id];
      if (raw === undefined || raw.trim() === "") continue;
      if (Number(raw) < 0) {
        setLocalError("La cantidad recibida no puede ser negativa.");
        return null;
      }
      items.push({ transferItemId: Number(item.id), quantityReceived: Number(raw) });
    }
    if (items.length === 0) {
      setLocalError("Ingresa la cantidad recibida (puede ser 0) de al menos una línea.");
      return null;
    }
    return items;
  }

  function handleContinue() {
    if (buildItems()) setConfirming(true);
  }

  function handleConfirm() {
    const items = buildItems();
    if (items) mutation.mutate(items);
  }

  const readyItems = pendingItems.filter((item) => quantities[item.id]?.trim());

  if (confirming) {
    return (
      <Modal
        title="Confirmar recepción"
        onClose={() => {
          mutation.reset();
          setConfirming(false);
        }}
      >
        <p>Se registrará la siguiente recepción para {transfer.transferNumber}:</p>
        <ul>
          {readyItems.map((item) => (
            <li key={item.id}>
              {productLabel(productsById.get(item.productId), item.productId)}: {quantities[item.id]}
              {Number(quantities[item.id]) < (item.quantityShipped ?? 0) ? " (dejará faltante)" : ""}
            </li>
          ))}
        </ul>
        <p className="state__hint">El stock de la sucursal destino se incrementa de inmediato.</p>
        <FormErrorMessage error={mutation.error} />
        <div className="modal__actions">
          <button
            type="button"
            onClick={() => {
              mutation.reset();
              setConfirming(false);
            }}
            disabled={mutation.isPending}
          >
            Volver
          </button>
          <button type="button" onClick={handleConfirm} disabled={mutation.isPending}>
            {mutation.isPending ? "Registrando…" : "Registrar recepción"}
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal title={`Recibir ${transfer.transferNumber}`} onClose={onClose}>
      <div style={{ overflowX: "auto" }}>
      <table>
        <thead>
          <tr>
            <th scope="col">Producto</th>
            <th scope="col">Enviado</th>
            <th scope="col">Cantidad recibida</th>
          </tr>
        </thead>
        <tbody>
          {transfer.items.map((item) => (
            <tr key={item.id}>
              <td>{productLabel(productsById.get(item.productId), item.productId)}</td>
              <td>{item.quantityShipped}</td>
              <td>
                {item.quantityReceived !== null ? (
                  <span className="state__hint">Ya recibida: {item.quantityReceived}</span>
                ) : (
                  <input
                    aria-label={`Cantidad recibida de ${productLabel(productsById.get(item.productId), item.productId)}`}
                    inputMode="decimal"
                    value={quantities[item.id] ?? ""}
                    onChange={(event) => setQuantities((current) => ({ ...current, [item.id]: event.target.value }))}
                  />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>

      {localError ? <p role="alert" className="field__error">{localError}</p> : null}

      <div className="modal__actions">
        <button type="button" onClick={onClose}>
          Cancelar
        </button>
        <button type="button" onClick={handleContinue}>
          Continuar
        </button>
      </div>
    </Modal>
  );
}
