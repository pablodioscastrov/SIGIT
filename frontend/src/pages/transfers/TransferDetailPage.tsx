import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { getTransfer, rejectTransfer } from "../../api/endpoints/transfers";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { canTransfers, canWriteInBranch } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { AsyncBoundary } from "../../components/state/states";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { useTransferRealtime } from "../../hooks/useTransferRealtime";
import type { TransferItem } from "../../types/api";
import { productLabel, useProductIndex } from "../products/useCatalog";
import { ApproveDialog } from "./ApproveDialog";
import { isStateConflict } from "./conflicts";
import { DiscrepancyDialog } from "./DiscrepancyDialog";
import { DispatchDialog } from "./DispatchDialog";
import { DISCREPANCY_TREATMENT_LABELS, TRANSFER_STATUS_LABELS } from "./labels";
import { ReceiveDialog } from "./ReceiveDialog";
import { Timeline } from "./Timeline";

type ActionDialog = "approve" | "dispatch" | "receive" | "reject" | { discrepancy: TransferItem } | null;

/**
 * Detalle de una transferencia: cabecera, línea de tiempo derivada de los
 * hitos ya persistidos, líneas con sus cantidades en cada etapa, faltantes y
 * tratamiento, y las acciones que corresponden al estado actual.
 *
 * <p>Las acciones visibles dependen de estado + rol + sucursal
 * (`canTransfers` + `canWriteInBranch`), pero es únicamente para no ofrecer
 * un botón que el backend rechazaría — la autorización real sigue siendo de
 * `@PreAuthorize` + `AuthorizationService` en el servidor.
 */
export function TransferDetailPage() {
  const { id = "" } = useParams();
  // React Router no remonta el elemento de ruta cuando solo cambia el
  // parámetro (navegar de /transferencias/2 a /transferencias/9 reutiliza la
  // misma instancia) — sin la `key`, el diálogo abierto (p. ej. "Tratar
  // faltante" desde el enlace a la transferencia de reposición) seguiría
  // montado con su estado de la transferencia anterior mientras la página ya
  // muestra la nueva. La `key={id}` fuerza un montaje fresco por transferencia.
  return <TransferDetailView key={id} id={id} />;
}

function TransferDetailView({ id }: { id: string }) {
  const { user } = useAuth();
  useTransferRealtime(user);
  const queryClient = useQueryClient();

  const query = useQuery({ queryKey: queryKeys.transfer(id), queryFn: () => getTransfer(id) });
  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const { byId: productsById } = useProductIndex();

  const [dialog, setDialog] = useState<ActionDialog>(null);
  const [conflictMessage, setConflictMessage] = useState<string | undefined>();

  function handleConflict() {
    setDialog(null);
    setConflictMessage(
      "Alguien más ya cambió el estado de esta transferencia mientras la pantalla estaba abierta. Se actualizó la información con lo más reciente.",
    );
    void queryClient.invalidateQueries({ queryKey: queryKeys.transfer(id) });
  }

  const rejectMutation = useMutation({
    mutationFn: () => rejectTransfer(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.transfer(id) });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.transfers });
      setDialog(null);
    },
    onError: (error) => {
      if (isStateConflict(error)) handleConflict();
    },
  });

  return (
    <section>
      <div className="page__header">
        <h1>Transferencia</h1>
        <Link to="/transferencias">Volver a transferencias</Link>
      </div>

      {conflictMessage ? (
        <div role="alert" className="conflict-banner">
          <p>{conflictMessage}</p>
          <button type="button" onClick={() => setConflictMessage(undefined)}>
            Entendido
          </button>
        </div>
      ) : null}

      <AsyncBoundary isLoading={query.isPending} error={query.error} data={query.data} onRetry={() => query.refetch()}>
        {(transfer) => {
          const branchName = (id: string) => branchesQuery.data?.content.find((b) => b.id === id)?.name ?? id;
          const canApprove = canTransfers.approve(user?.role) && canWriteInBranch(user ?? null, transfer.originBranchId);
          const canDispatch = canTransfers.dispatch(user?.role) && canWriteInBranch(user ?? null, transfer.originBranchId);
          const canReceive = canTransfers.receive(user?.role) && canWriteInBranch(user ?? null, transfer.destinationBranchId);
          const canTreat =
            canTransfers.treatDiscrepancy(user?.role) &&
            (canWriteInBranch(user ?? null, transfer.originBranchId) || canWriteInBranch(user ?? null, transfer.destinationBranchId));

          const untreatedShortages = transfer.items.filter(
            (item) => item.quantityMissing !== null && item.quantityMissing > 0 && item.discrepancyTreatment === null,
          );

          const onTime =
            transfer.receivedAt && transfer.estimatedArrivalDate
              ? new Date(transfer.receivedAt) <= new Date(`${transfer.estimatedArrivalDate}T23:59:59`)
              : null;

          return (
            <>
              <dl className="detail-grid">
                <div><dt>Número</dt><dd>{transfer.transferNumber}</dd></div>
                <div><dt>Estado</dt><dd><span className="badge badge--ok">{TRANSFER_STATUS_LABELS[transfer.status]}</span></dd></div>
                <div><dt>Origen</dt><dd>{branchName(transfer.originBranchId)}</dd></div>
                <div><dt>Destino</dt><dd>{branchName(transfer.destinationBranchId)}</dd></div>
                <div><dt>Urgente</dt><dd>{transfer.urgency ? "Sí" : "No"}</dd></div>
                <div><dt>Transportista</dt><dd>{transfer.carrierName ?? "—"}</dd></div>
                <div>
                  <dt>Llegada estimada vs. real</dt>
                  <dd>
                    {transfer.estimatedArrivalDate ?? "—"} vs. {transfer.receivedAt ? new Date(transfer.receivedAt).toLocaleDateString() : "—"}
                    {onTime !== null ? (
                      <span className={onTime ? "badge badge--ok" : "badge badge--warn"}> {onTime ? "A tiempo" : "Tardía"}</span>
                    ) : null}
                  </dd>
                </div>
              </dl>

              <h2>Historial</h2>
              <Timeline transfer={transfer} />

              <h2>Líneas</h2>
              <div style={{ overflowX: "auto" }}>
              <table>
                <thead>
                  <tr>
                    <th scope="col">Producto</th>
                    <th scope="col">Solicitado</th>
                    <th scope="col">Aprobado</th>
                    <th scope="col">Despachado</th>
                    <th scope="col">Recibido</th>
                    <th scope="col">Faltante</th>
                    <th scope="col">Tratamiento</th>
                    <th scope="col"></th>
                  </tr>
                </thead>
                <tbody>
                  {transfer.items.map((item) => (
                    <tr key={item.id}>
                      <td>{productLabel(productsById.get(item.productId), item.productId)}</td>
                      <td>{item.quantityRequested}</td>
                      <td>{item.quantityApproved ?? "—"}</td>
                      <td>{item.quantityShipped ?? "—"}</td>
                      <td>{item.quantityReceived ?? "—"}</td>
                      <td>{item.quantityMissing ?? "—"}</td>
                      <td>
                        {item.discrepancyTreatment ? (
                          <>
                            {DISCREPANCY_TREATMENT_LABELS[item.discrepancyTreatment]}
                            {item.followUpTransferId ? (
                              <>
                                {" "}
                                (<Link to={`/transferencias/${item.followUpTransferId}`}>ver reposición</Link>)
                              </>
                            ) : null}
                            {item.treatmentNotes ? <p className="state__hint">{item.treatmentNotes}</p> : null}
                          </>
                        ) : item.quantityMissing ? (
                          "Sin tratar"
                        ) : (
                          "—"
                        )}
                      </td>
                      <td>
                        {canTreat && item.quantityMissing !== null && item.quantityMissing > 0 && item.discrepancyTreatment === null ? (
                          <button type="button" onClick={() => setDialog({ discrepancy: item })}>
                            Tratar faltante
                          </button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>

              {transfer.status === "REQUESTED" && canApprove ? (
                <div className="modal__actions">
                  <button type="button" onClick={() => setDialog("approve")}>
                    Aprobar
                  </button>
                  <button type="button" onClick={() => setDialog("reject")}>
                    Rechazar
                  </button>
                </div>
              ) : null}

              {transfer.status === "APPROVED" && canDispatch ? (
                <div className="modal__actions">
                  <button type="button" onClick={() => setDialog("dispatch")}>
                    Despachar
                  </button>
                </div>
              ) : null}

              {transfer.status === "IN_TRANSIT" && canReceive ? (
                <div className="modal__actions">
                  <button type="button" onClick={() => setDialog("receive")}>
                    Recibir
                  </button>
                </div>
              ) : null}

              {transfer.status === "RECEIVED_PARTIAL" && untreatedShortages.length > 0 ? (
                <p className="state__hint">
                  Esta transferencia tiene {untreatedShortages.length} línea(s) con faltante sin tratar — trátalas desde la tabla de arriba.
                </p>
              ) : null}

              {dialog === "approve" ? <ApproveDialog transfer={transfer} onClose={() => setDialog(null)} onConflict={handleConflict} /> : null}
              {dialog === "dispatch" ? <DispatchDialog transfer={transfer} onClose={() => setDialog(null)} onConflict={handleConflict} /> : null}
              {dialog === "receive" ? <ReceiveDialog transfer={transfer} onClose={() => setDialog(null)} onConflict={handleConflict} /> : null}
              {dialog && typeof dialog === "object" && "discrepancy" in dialog ? (
                <DiscrepancyDialog transfer={transfer} item={dialog.discrepancy} onClose={() => setDialog(null)} onConflict={handleConflict} />
              ) : null}
              {dialog === "reject" ? (
                <ConfirmDialog
                  title="Rechazar transferencia"
                  message={`La solicitud ${transfer.transferNumber} pasará a Rechazada. No podrá aprobarse ni despacharse después.`}
                  confirmLabel="Rechazar"
                  isPending={rejectMutation.isPending}
                  error={rejectMutation.error}
                  onConfirm={() => rejectMutation.mutate()}
                  onCancel={() => setDialog(null)}
                />
              ) : null}
            </>
          );
        }}
      </AsyncBoundary>
    </section>
  );
}
