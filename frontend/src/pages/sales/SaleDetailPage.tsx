import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { createSaleReturn, getSale } from "../../api/endpoints/sales";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { canSales } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { FormErrorMessage } from "../../components/form/Field";
import { AsyncBoundary } from "../../components/state/states";
import { Modal } from "../../components/ui/Modal";
import { useIdempotencyKey } from "../../hooks/useIdempotencyKey";
import { formatCurrency } from "../../lib/currency";
import type { CreateSaleReturnItemRequest } from "../../types/api";
import { productLabel, useProductIndex, useUnitsOfMeasure } from "../products/useCatalog";

/**
 * Comprobante de venta (RF-021) y, cuando corresponde, la devolución de sus
 * líneas (BR-052). El comprobante original nunca se recalcula ni cambia de
 * estado: la devolución solo repone stock y queda registrada por línea
 * (`quantityReturned`/`pending`), mismo criterio de "documento inmutable +
 * operación posterior" que ya usa la recepción de compra.
 *
 * <p>El formulario de devolución vive detrás de un botón ("Generar
 * devolución") en vez de mostrarse siempre en la tabla — mostrar inputs de
 * cantidad junto a cada línea, incluso cuando nadie iba a devolver nada,
 * resultaba confuso (feedback explícito de uso).
 */
export function SaleDetailPage() {
  const { id = "" } = useParams();
  const { user } = useAuth();
  const canReturn = canSales.write(user?.role);
  const queryClient = useQueryClient();
  const idempotency = useIdempotencyKey();

  const query = useQuery({ queryKey: queryKeys.sale(id), queryFn: () => getSale(id) });
  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const { byId: productsById } = useProductIndex();
  const { byId: unitsById } = useUnitsOfMeasure();

  const [returning, setReturning] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [localError, setLocalError] = useState<string | undefined>();

  const returnMutation = useMutation({
    mutationFn: (items: CreateSaleReturnItemRequest[]) => createSaleReturn(id, { items }, idempotency.key),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.sale(id) });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventory });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventoryMovements });
      idempotency.renew();
      setDraft({});
      setConfirming(false);
      setReturning(false);
    },
    onError: () => {
      // Un 422 aquí puede deberse a una devolución concurrente que ya cambió
      // lo pendiente: se relee la venta para no seguir operando sobre cifras
      // obsoletas (mismo criterio que la recepción de compra).
      void queryClient.invalidateQueries({ queryKey: queryKeys.sale(id) });
    },
  });

  function openReturnFlow() {
    setDraft({});
    setLocalError(undefined);
    returnMutation.reset();
    setConfirming(false);
    setReturning(true);
  }

  function closeReturnFlow() {
    returnMutation.reset();
    setConfirming(false);
    setReturning(false);
  }

  function buildReturnItems(): CreateSaleReturnItemRequest[] | null {
    setLocalError(undefined);
    const items: CreateSaleReturnItemRequest[] = [];
    for (const [saleItemId, value] of Object.entries(draft)) {
      const quantity = value.trim();
      if (!quantity) continue;
      if (!(Number(quantity) > 0)) {
        setLocalError("La cantidad a devolver debe ser mayor que cero.");
        return null;
      }
      items.push({ saleItemId: Number(saleItemId), quantity: Number(quantity) });
    }
    if (items.length === 0) {
      setLocalError("Ingresa la cantidad a devolver de al menos una línea.");
      return null;
    }
    return items;
  }

  function handleContinue() {
    if (buildReturnItems()) setConfirming(true);
  }

  function handleConfirm() {
    const items = buildReturnItems();
    if (items) returnMutation.mutate(items);
  }

  return (
    <section>
      <div className="page__header">
        <h1>Comprobante de venta</h1>
        <Link to="/ventas">Volver a ventas</Link>
      </div>

      <AsyncBoundary isLoading={query.isPending} error={query.error} data={query.data} onRetry={() => query.refetch()}>
        {(sale) => {
          const returnableItems = sale.items.filter((item) => item.pending > 0);
          const canReturnNow = canReturn && returnableItems.length > 0;
          const returnDraftItems = Object.entries(draft)
            .filter(([, value]) => value.trim())
            .map(([saleItemId, value]) => ({ item: sale.items.find((i) => i.id === saleItemId)!, quantity: value }));

          return (
            <>
              <dl className="detail-grid">
                <div><dt>Número</dt><dd>{sale.saleNumber}</dd></div>
                <div><dt>Sucursal</dt><dd>{branchesQuery.data?.content.find((b) => b.id === sale.branchId)?.name ?? sale.branchId}</dd></div>
                <div><dt>Fecha</dt><dd>{new Date(sale.saleDate).toLocaleString()}</dd></div>
                <div><dt>Responsable</dt><dd>{sale.soldByUserName ?? sale.soldByUserId}</dd></div>
                <div><dt>Estado</dt><dd><span className="badge badge--ok">{sale.status}</span></dd></div>
              </dl>

              <div style={{ overflowX: "auto" }}>
              <table>
                <thead>
                  <tr>
                    <th scope="col">Producto</th>
                    <th scope="col">Cantidad</th>
                    <th scope="col">Unidad</th>
                    <th scope="col">Precio unitario</th>
                    <th scope="col">Descuento %</th>
                    <th scope="col">Total línea</th>
                    <th scope="col">Devuelto</th>
                    <th scope="col">Pendiente</th>
                  </tr>
                </thead>
                <tbody>
                  {sale.items.map((item) => (
                    <tr key={item.id}>
                      <td>{productLabel(productsById.get(item.productId), item.productId)}</td>
                      <td>{item.quantity}</td>
                      <td>{unitsById.get(item.unitOfMeasureId)?.code ?? item.unitOfMeasureId}</td>
                      <td>{formatCurrency(item.unitPrice)}</td>
                      <td>{item.discountPercentage}</td>
                      <td>{formatCurrency(item.lineTotal)}</td>
                      <td>{item.quantityReturned}</td>
                      <td>{item.pending}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>

              <dl className="detail-grid">
                <div><dt>Subtotal</dt><dd>{formatCurrency(sale.subtotal)}</dd></div>
                <div><dt>Descuento</dt><dd>{formatCurrency(sale.discountTotal)}</dd></div>
                <div><dt>Total</dt><dd><strong>{formatCurrency(sale.total)}</strong></dd></div>
              </dl>

              {canReturnNow ? (
                <button type="button" onClick={openReturnFlow}>
                  Generar devolución
                </button>
              ) : null}

              {returning ? (
                <Modal title={confirming ? "Confirmar devolución" : "Generar devolución"} onClose={closeReturnFlow}>
                  {!confirming ? (
                    <>
                      <p>Indica la cantidad a devolver de cada línea que corresponda:</p>
                      <div style={{ overflowX: "auto" }}>
                      <table>
                        <thead>
                          <tr>
                            <th scope="col">Producto</th>
                            <th scope="col">Vendido</th>
                            <th scope="col">Pendiente</th>
                            <th scope="col">Cantidad a devolver</th>
                          </tr>
                        </thead>
                        <tbody>
                          {returnableItems.map((item) => (
                            <tr key={item.id}>
                              <td>{productLabel(productsById.get(item.productId), item.productId)}</td>
                              <td>{item.quantity}</td>
                              <td>{item.pending}</td>
                              <td>
                                <input
                                  aria-label={`Cantidad a devolver de ${productLabel(productsById.get(item.productId), item.productId)}`}
                                  placeholder="Cantidad"
                                  inputMode="decimal"
                                  value={draft[item.id] ?? ""}
                                  onChange={(event) => setDraft((current) => ({ ...current, [item.id]: event.target.value }))}
                                />
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                      </div>
                      {localError ? <p role="alert" className="field__error">{localError}</p> : null}
                      <FormErrorMessage error={returnMutation.error} />
                      <div className="modal__actions">
                        <button type="button" onClick={closeReturnFlow}>
                          Cancelar
                        </button>
                        <button type="button" onClick={handleContinue}>
                          Continuar
                        </button>
                      </div>
                    </>
                  ) : (
                    <>
                      <p>Se registrará la siguiente devolución para la venta {sale.saleNumber}:</p>
                      <ul>
                        {returnDraftItems.map(({ item, quantity }) => (
                          <li key={item.id}>
                            {productLabel(productsById.get(item.productId), item.productId)}: {quantity}{" "}
                            {unitsById.get(item.unitOfMeasureId)?.code}
                          </li>
                        ))}
                      </ul>
                      <p className="state__hint">El inventario de la sucursal aumenta de inmediato. El comprobante original no cambia.</p>
                      <FormErrorMessage error={returnMutation.error} />
                      <div className="modal__actions">
                        <button type="button" onClick={() => setConfirming(false)} disabled={returnMutation.isPending}>
                          Volver
                        </button>
                        <button type="button" onClick={handleConfirm} disabled={returnMutation.isPending}>
                          {returnMutation.isPending ? "Registrando…" : "Registrar devolución"}
                        </button>
                      </div>
                    </>
                  )}
                </Modal>
              ) : null}
            </>
          );
        }}
      </AsyncBoundary>
    </section>
  );
}
