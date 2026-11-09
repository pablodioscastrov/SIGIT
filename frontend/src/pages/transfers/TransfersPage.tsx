import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { exportTransfers } from "../../api/endpoints/reports";
import { listTransfers } from "../../api/endpoints/transfers";
import { queryKeys } from "../../api/queryClient";
import { canTransfers } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { AsyncBoundary } from "../../components/state/states";
import { ExportDialog } from "../../components/ui/ExportDialog";
import { Pagination } from "../../components/ui/Pagination";
import { useTransferRealtime } from "../../hooks/useTransferRealtime";
import type { TransferStatus } from "../../types/api";
import { TRANSFER_STATUS_LABELS } from "./labels";

const PAGE_SIZE = 10;
const STATUSES: TransferStatus[] = [
  "REQUESTED",
  "APPROVED",
  "REJECTED",
  "IN_TRANSIT",
  "RECEIVED_COMPLETE",
  "RECEIVED_PARTIAL",
  "CLOSED",
];

/**
 * Listado de transferencias. La lectura es abierta a cualquier rol de las
 * sucursales origen/destino (`ADMIN` sin restricción) — el backend acota
 * automáticamente lo que ve un no-`ADMIN` sin importar qué `branchId` se
 * envíe, así que el selector de sucursal solo aporta algo a `ADMIN`.
 *
 * <p>El filtro `role=origin|destination` sí tiene sentido para cualquier rol:
 * distingue "transferencias que mi sucursal envía" de "las que recibe".
 */
export function TransfersPage() {
  const { user } = useAuth();
  useTransferRealtime(user);
  const canRequest = canTransfers.request(user?.role);
  const isAdmin = user?.role === "ADMIN";

  const [branchId, setBranchId] = useState("");
  const [role, setRole] = useState<"" | "origin" | "destination">("");
  const [status, setStatus] = useState<TransferStatus | "">("");
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);

  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const branchesById = new Map((branchesQuery.data?.content ?? []).map((branch) => [branch.id, branch]));

  const params = {
    branchId: isAdmin ? branchId || undefined : undefined,
    role: role || undefined,
    status: status || undefined,
    page,
    size: PAGE_SIZE,
  };
  const query = useQuery({ queryKey: queryKeys.transfers(params), queryFn: () => listTransfers(params) });

  function changeFilter(apply: () => void) {
    apply();
    setPage(0);
  }

  return (
    <section>
      <div className="page__header">
        <h1>Transferencias</h1>
        <div className="page__actions">
          <button type="button" onClick={() => setExporting(true)}>
            Exportar a Excel
          </button>
          {canRequest ? (
            <Link to="/transferencias/nueva">
              <button type="button">Solicitar transferencia</button>
            </Link>
          ) : null}
        </div>
      </div>

      {exporting ? (
        <ExportDialog
          title="Exportar transferencias"
          onClose={() => setExporting(false)}
          onExport={(range) =>
            exportTransfers({ branchId: isAdmin ? branchId || undefined : undefined, status: status || undefined, ...range })
          }
        />
      ) : null}

      <form className="filters" onSubmit={(event) => event.preventDefault()}>
        {isAdmin ? (
          <div className="field">
            <label htmlFor="transfers-branch">Sucursal</label>
            <select id="transfers-branch" value={branchId} onChange={(event) => changeFilter(() => setBranchId(event.target.value))}>
              <option value="">Todas las sucursales</option>
              {(branchesQuery.data?.content ?? []).map((branch) => (
                <option key={branch.id} value={branch.id}>
                  {branch.name}
                </option>
              ))}
            </select>
          </div>
        ) : null}
        <div className="field">
          <label htmlFor="transfers-role">Rol de mi sucursal</label>
          <select id="transfers-role" value={role} onChange={(event) => changeFilter(() => setRole(event.target.value as typeof role))}>
            <option value="">Origen o destino</option>
            <option value="origin">Origen (envía)</option>
            <option value="destination">Destino (recibe)</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="transfers-status">Estado</label>
          <select id="transfers-status" value={status} onChange={(event) => changeFilter(() => setStatus(event.target.value as TransferStatus))}>
            <option value="">Todos</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {TRANSFER_STATUS_LABELS[value]}
              </option>
            ))}
          </select>
        </div>
      </form>

      <AsyncBoundary
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        onRetry={() => query.refetch()}
        isEmpty={(result) => result.content.length === 0}
        emptyTitle="No hay transferencias que coincidan con los filtros."
      >
        {(result) => (
          <>
            <div style={{ overflowX: "auto" }}>
            <table>
              <caption>{result.totalElements} transferencia(s)</caption>
              <thead>
                <tr>
                  <th scope="col">Transferencia</th>
                  <th scope="col">Origen</th>
                  <th scope="col">Destino</th>
                  <th scope="col">Estado</th>
                  <th scope="col">Urgente</th>
                  <th scope="col">Llegada estimada</th>
                  <th scope="col">Acciones</th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((transfer) => (
                  <tr key={transfer.id}>
                    <td>{transfer.transferNumber}</td>
                    <td>{branchesById.get(transfer.originBranchId)?.name ?? transfer.originBranchId}</td>
                    <td>{branchesById.get(transfer.destinationBranchId)?.name ?? transfer.destinationBranchId}</td>
                    <td>
                      <span className={transfer.status === "REJECTED" ? "badge badge--muted" : "badge badge--ok"}>
                        {TRANSFER_STATUS_LABELS[transfer.status]}
                      </span>
                    </td>
                    <td>{transfer.urgency ? "Sí" : "No"}</td>
                    <td>{transfer.estimatedArrivalDate ?? "—"}</td>
                    <td>
                      <Link to={`/transferencias/${transfer.id}`}>Ver</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
            <Pagination page={result} onPageChange={setPage} />
          </>
        )}
      </AsyncBoundary>
    </section>
  );
}
