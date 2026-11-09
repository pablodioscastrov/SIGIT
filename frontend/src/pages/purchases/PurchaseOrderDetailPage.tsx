import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { cancelPurchaseOrder, getPurchaseOrder, receivePurchaseOrder } from "../../api/endpoints/purchases";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { canPurchases } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { FormErrorMessage } from "../../components/form/Field";
import { AsyncBoundary } from "../../components/state/states";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { Modal } from "../../components/ui/Modal";
import { useIdempotencyKey } from "../../hooks/useIdempotencyKey";
import { formatCurrency } from "../../lib/currency";
import { productLabel, useProductIndex, useUnitsOfMeasure } from "../products/useCatalog";
import type { ReceiptItemRequest } from "../../types/api";
import { useActiveSuppliers } from "./useSuppliers";
import { PURCHASE_STATUS_LABELS, orderTotal } from "./statusLabels";

interface ReceiptDraft {
  quantityReceived: string;
  unitPrice: string;
}

/**
 * Detalle de orden de compra: cabecera, líneas y, cuando corresponde, el
 * formulario de recepción. La recepción afecta stock real y recalcula el
 * costo promedio (BR-004), así que pasa por un resumen de confirmación —
 * igual criterio que el ajuste manual de inventario (ADR-011).
 */
export function PurchaseOrderDetailPage() {
  const { id = "" } = useParams();
  const { user } = useAuth();
  const canWrite = canPurchases.write(user?.role);
  const queryClient = useQueryClient();
  const idempotency = useIdempotencyKey();

  const query = useQuery({ queryKey: queryKeys.purchaseOrder(id), queryFn: () => getPurchaseOrder(id) });
  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const { byId: suppliersById } = useActiveSuppliers();
  const { byId: productsById } = useProductIndex();
  const { byId: unitsById } = useUnitsOfMeasure();

  const [draft, setDraft] = useState<Record<string, ReceiptDraft>>({});
  const [confirming, setConfirming] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [localError, setLocalError] = useState<string | undefined>();

  const cancelMutation = useMutation({
    mutationFn: () => cancelPurchaseOrder(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.purchaseOrder(id) });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.purchaseOrders });
      setCancelling(false);
    },
  });

  const receiveMutation = useMutation({
    mutationFn: (items: ReceiptItemRequest[]) => receivePurchaseOrder(id, { items }, idempotency.key),
    onSuccess: () => {
      // La respuesta trae solo el resumen de la recepción; el detalle
      // completo (pendientes, estado) se relee de la API, fuente de verdad.
      void queryClient.invalidateQueries({ queryKey: queryKeys.purchaseOrder(id) });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.purchaseOrders });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventory });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventoryMovements });
      idempotency.renew();
      setDraft({});
      setConfirming(false);
    },
    onError: () => {
      // Un 422/409 aquí puede deberse a una recepción concurrente que ya
      // cambió lo pendiente: se relee el pedido para no seguir operando
      // sobre cifras obsoletas.
      void queryClient.invalidateQueries({ queryKey: queryKeys.purchaseOrder(id) });
    },
  });

  function updateDraft(itemId: string, patch: Partial<ReceiptDraft>) {
    setDraft((current) => {
      const existing: ReceiptDraft = current[itemId] ?? { quantityReceived: "", unitPrice: "" };
      return { ...current, [itemId]: { ...existing, ...patch } };
    });
  }

  function buildReceiptItems(): ReceiptItemRequest[] | null {
    setLocalError(undefined);
    const items: ReceiptItemRequest[] = [];
    for (const [itemId, values] of Object.entries(draft)) {
      const quantity = values.quantityReceived.trim();
      if (!quantity) continue;
      if (!(Number(quantity) > 0)) {
        setLocalError("La cantidad a recibir debe ser mayor que cero.");
        return null;
      }
      if (!values.unitPrice.trim() || !(Number(values.unitPrice) > 0)) {
        setLocalError("El precio de recepción debe ser mayor que cero.");
        return null;
      }
      items.push({ purchaseOrderItemId: Number(itemId), quantityReceived: Number(quantity), unitPrice: Number(values.unitPrice) });
    }
    if (items.length === 0) {
      setLocalError("Ingresa la cantidad recibida de al menos una línea.");
      return null;
    }
    return items;
  }

  function handleContinue() {
    if (buildReceiptItems()) setConfirming(true);
  }

  function handleConfirm() {
    const items = buildReceiptItems();
    if (items) receiveMutation.mutate(items);
  }

  return (
    <section>
      <div className="page__header">
        <h1>Orden de compra</h1>
        <Link to="/compras">Volver a compras</Link>
      </div>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
      >
        {(order) => {
          const receivableItems = order.items.filter((item) => item.pending > 0);
          const canReceive = canWrite && (order.status === "CREATED" || order.status === "PARTIALLY_RECEIVED");
          const receiptItems = Object.entries(draft)
            .filter(([, v]) => v.quantityReceived.trim())
            .map(([itemId, v]) => {
              const item = order.items.find((i) => i.id === itemId)!;
              return { item, quantityReceived: v.quantityReceived, unitPrice: v.unitPrice || String(item.unitPrice) };
            });

          return (
            <>
              <dl className="detail-grid">
                <div><dt>Número</dt><dd>{order.orderNumber}</dd></div>
                <div><dt>Proveedor</dt><dd>{suppliersById.get(order.supplierId)?.name ?? order.supplierId}</dd></div>
                <div><dt>Sucursal</dt><dd>{branchesQuery.data?.content.find((b) => b.id === order.branchId)?.name ?? order.branchId}</dd></div>
                <div><dt>Fecha</dt><dd>{new Date(order.orderDate).toLocaleString()}</dd></div>
                <div><dt>Condición de pago</dt><dd>{order.paymentTerm ?? "—"}</dd></div>
                <div><dt>Estado</dt><dd><span className="badge badge--ok">{PURCHASE_STATUS_LABELS[order.status]}</span></dd></div>
                <div><dt>Total</dt><dd>{formatCurrency(orderTotal(order.items))}</dd></div>
              </dl>

              {canWrite && order.status === "CREATED" ? (
                <button type="button" onClick={() => setCancelling(true)}>
                  Cancelar orden
                </button>
              ) : null}

              <h2>Líneas</h2>
              <div style={{ overflowX: "auto" }}>
              <table>
                <thead>
                  <tr>
                    <th scope="col">Producto</th>
                    <th scope="col">Unidad</th>
                    <th scope="col">Ordenado</th>
                    <th scope="col">Recibido</th>
                    <th scope="col">Pendiente</th>
                    <th scope="col">Precio unitario</th>
                    <th scope="col">Descuento %</th>
                    <th scope="col">Total línea</th>
                    {canReceive ? <th scope="col">Recibir ahora</th> : null}
                  </tr>
                </thead>
                <tbody>
                  {order.items.map((item) => (
                    <tr key={item.id}>
                      <td>{productLabel(productsById.get(item.productId), item.productId)}</td>
                      <td>{unitsById.get(item.unitOfMeasureId)?.code ?? item.unitOfMeasureId}</td>
                      <td>{item.quantityOrdered}</td>
                      <td>{item.quantityReceived}</td>
                      <td>{item.pending}</td>
                      <td>{formatCurrency(item.unitPrice)}</td>
                      <td>{item.discountPercentage}</td>
                      <td>{formatCurrency(item.lineTotal)}</td>
                      {canReceive ? (
                        <td>
                          {item.pending > 0 ? (
                            <div className="receipt-inputs">
                              <input
                                aria-label={`Cantidad a recibir de ${item.productId}`}
                                placeholder="Cantidad"
                                inputMode="decimal"
                                value={draft[item.id]?.quantityReceived ?? ""}
                                onChange={(event) => updateDraft(item.id, { quantityReceived: event.target.value })}
                              />
                              <input
                                aria-label={`Precio de recepción de ${item.productId}`}
                                placeholder={`Precio (${formatCurrency(item.unitPrice)})`}
                                inputMode="decimal"
                                value={draft[item.id]?.unitPrice ?? ""}
                                onChange={(event) => updateDraft(item.id, { unitPrice: event.target.value })}
                              />
                            </div>
                          ) : (
                            <span className="state__hint">Completa</span>
                          )}
                        </td>
                      ) : null}
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>

              {canReceive && receivableItems.length > 0 ? (
                <div className="panel">
                  {localError ? <p role="alert" className="field__error">{localError}</p> : null}
                  {/* El error de la mutación se muestra en el modal de confirmación
                      mientras está abierto — repetirlo aquí detrás duplicaría el
                      mensaje en pantalla. */}
                  {!confirming ? <FormErrorMessage error={receiveMutation.error} /> : null}
                  <button type="button" onClick={handleContinue}>
                    Continuar
                  </button>
                </div>
              ) : null}

              {confirming ? (
                <Modal
                  title="Confirmar recepción"
                  onClose={() => {
                    receiveMutation.reset();
                    setConfirming(false);
                  }}
                >
                  <p>Se registrará la siguiente recepción para la orden {order.orderNumber}:</p>
                  <ul>
                    {receiptItems.map(({ item, quantityReceived, unitPrice }) => (
                      <li key={item.id}>
                        {productLabel(productsById.get(item.productId), item.productId)}: {quantityReceived}{" "}
                        {unitsById.get(item.unitOfMeasureId)?.code} a {formatCurrency(Number(unitPrice))} c/u
                      </li>
                    ))}
                  </ul>
                  <p className="state__hint">El inventario y el costo promedio de la sucursal se actualizan de inmediato.</p>
                  <FormErrorMessage error={receiveMutation.error} />
                  <div className="modal__actions">
                    <button
                      type="button"
                      onClick={() => {
                        receiveMutation.reset();
                        setConfirming(false);
                      }}
                      disabled={receiveMutation.isPending}
                    >
                      Volver
                    </button>
                    <button type="button" onClick={handleConfirm} disabled={receiveMutation.isPending}>
                      {receiveMutation.isPending ? "Registrando…" : "Registrar recepción"}
                    </button>
                  </div>
                </Modal>
              ) : null}

              {cancelling ? (
                <ConfirmDialog
                  title="Cancelar orden de compra"
                  message={`La orden ${order.orderNumber} pasará a Cancelada y ya no podrá recibirse mercancía contra ella.`}
                  confirmLabel="Cancelar orden"
                  isPending={cancelMutation.isPending}
                  onConfirm={() => cancelMutation.mutate()}
                  onCancel={() => setCancelling(false)}
                />
              ) : null}
            </>
          );
        }}
      </AsyncBoundary>
    </section>
  );
}
