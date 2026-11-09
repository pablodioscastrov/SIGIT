import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { dispatchTransfer } from "../../api/endpoints/transfers";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { FormErrorMessage } from "../../components/form/Field";
import { Modal } from "../../components/ui/Modal";
import type { DispatchTransferItemRequest, Transfer } from "../../types/api";
import { isStateConflict } from "./conflicts";
import { productLabel, useProductIndex } from "../products/useCatalog";

/**
 * Despacho (flujo D, BR-013): cubre **todas** las líneas en un solo evento de
 * envío — el contrato no admite despacho parcial por línea — y descuenta
 * stock real de inmediato, así que pasa por un resumen de confirmación antes
 * de enviarse, igual criterio que la recepción de compra (ADR-012). El
 * backend vuelve a validar la disponibilidad en este instante (BR-013,
 * escenario 3.2): esta pantalla nunca decide si alcanza el stock.
 */
export function DispatchDialog({
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
  const [carrierName, setCarrierName] = useState("");
  const [estimatedArrivalDate, setEstimatedArrivalDate] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>(() =>
    Object.fromEntries(transfer.items.map((item) => [item.id, String(item.quantityApproved ?? 0)])),
  );
  const [localError, setLocalError] = useState<string | undefined>();
  const [confirming, setConfirming] = useState(false);

  const mutation = useMutation({
    mutationFn: (items: DispatchTransferItemRequest[]) =>
      dispatchTransfer(transfer.id, {
        carrierName: carrierName.trim() || null,
        estimatedArrivalDate: estimatedArrivalDate || null,
        items,
      }),
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

  function buildItems(): DispatchTransferItemRequest[] | null {
    setLocalError(undefined);
    const items: DispatchTransferItemRequest[] = [];
    for (const item of transfer.items) {
      const value = quantities[item.id]?.trim();
      if (!value || !(Number(value) > 0)) {
        setLocalError("Cada línea necesita una cantidad despachada mayor que cero.");
        return null;
      }
      if (Number(value) > (item.quantityApproved ?? 0)) {
        setLocalError("Ninguna línea puede despacharse por más de lo aprobado.");
        return null;
      }
      items.push({ transferItemId: Number(item.id), quantityShipped: Number(value) });
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

  if (confirming) {
    return (
      <Modal
        title="Confirmar despacho"
        onClose={() => {
          mutation.reset();
          setConfirming(false);
        }}
      >
        <p>Se despachará la transferencia {transfer.transferNumber} con:</p>
        <ul>
          {transfer.items.map((item) => (
            <li key={item.id}>
              {productLabel(productsById.get(item.productId), item.productId)}: {quantities[item.id]}
            </li>
          ))}
        </ul>
        <p className="state__hint">
          {carrierName.trim() ? `Transportista: ${carrierName.trim()}. ` : ""}
          {estimatedArrivalDate ? `Llegada estimada: ${estimatedArrivalDate}. ` : ""}
          El stock de la sucursal origen se descuenta de inmediato.
        </p>
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
            {mutation.isPending ? "Despachando…" : "Confirmar despacho"}
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal title={`Despachar ${transfer.transferNumber}`} onClose={onClose}>
      <div className="filters">
        <div className="field">
          <label htmlFor="dispatch-carrier">Transportista</label>
          <input id="dispatch-carrier" value={carrierName} onChange={(event) => setCarrierName(event.target.value)} maxLength={150} />
        </div>
        <div className="field">
          <label htmlFor="dispatch-eta">Llegada estimada</label>
          <input
            id="dispatch-eta"
            type="date"
            value={estimatedArrivalDate}
            onChange={(event) => setEstimatedArrivalDate(event.target.value)}
          />
        </div>
      </div>

      <div style={{ overflowX: "auto" }}>
      <table>
        <thead>
          <tr>
            <th scope="col">Producto</th>
            <th scope="col">Aprobado</th>
            <th scope="col">Cantidad a despachar</th>
          </tr>
        </thead>
        <tbody>
          {transfer.items.map((item) => (
            <tr key={item.id}>
              <td>{productLabel(productsById.get(item.productId), item.productId)}</td>
              <td>{item.quantityApproved}</td>
              <td>
                <input
                  aria-label={`Cantidad a despachar de ${productLabel(productsById.get(item.productId), item.productId)}`}
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
