import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getActiveTransfersDashboard } from "../../api/endpoints/dashboard";
import { queryKeys } from "../../api/queryClient";
import { AsyncBoundary } from "../../components/state/states";
import { TRANSFER_STATUS_LABELS } from "../transfers/labels";

/**
 * Transferencias activas y su impacto en inventario (RF-033, BR-041). Se
 * suscribe al mismo canal SSE que la pantalla de transferencias
 * (`useTransferRealtime`, montado por la página contenedora) — cuando una
 * transferencia cambia de estado, este panel se refresca solo.
 */
export function ActiveTransfersPanel({ branchId }: { branchId: string }) {
  const query = useQuery({
    queryKey: queryKeys.dashboardActiveTransfers(branchId),
    queryFn: () => getActiveTransfersDashboard({ branchId }),
  });

  return (
    <section className="panel dashboard-panel">
      <h2>Transferencias activas</h2>
      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.transfers.length === 0}
        emptyTitle="No hay transferencias activas para esta sucursal."
      >
        {(result) => (
          <>
            <dl className="detail-grid">
              <div><dt>Activas</dt><dd>{result.activeCount}</dd></div>
              <div><dt>Unidades en tránsito</dt><dd>{result.totalUnitsInTransit}</dd></div>
              <div><dt>Unidades pendientes de despacho</dt><dd>{result.totalUnitsPendingDispatch}</dd></div>
            </dl>
            <div style={{ overflowX: "auto" }}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Transferencia</th>
                  <th scope="col">Estado</th>
                  <th scope="col">Urgente</th>
                  <th scope="col">En tránsito</th>
                  <th scope="col">Pendiente de despacho</th>
                </tr>
              </thead>
              <tbody>
                {result.transfers.map((transfer) => (
                  <tr key={transfer.transferId}>
                    <td>
                      <Link to={`/transferencias/${transfer.transferId}`}>{transfer.transferNumber}</Link>
                    </td>
                    <td>{TRANSFER_STATUS_LABELS[transfer.status]}</td>
                    <td>{transfer.urgency ? "Sí" : "No"}</td>
                    <td>{transfer.unitsInTransit}</td>
                    <td>{transfer.unitsPendingDispatch}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          </>
        )}
      </AsyncBoundary>
    </section>
  );
}
