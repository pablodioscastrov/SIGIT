import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { queryKeys } from "../../api/queryClient";
import { canDashboard } from "../../auth/permissions";
import { useAuth } from "../../auth/useAuth";
import { useTransferRealtime } from "../../hooks/useTransferRealtime";
import { ActiveTransfersPanel } from "./ActiveTransfersPanel";
import { InventoryDemandPanel } from "./InventoryDemandPanel";
import { ReplenishmentPanel } from "./ReplenishmentPanel";
import { SalesTrendPanel } from "./SalesTrendPanel";

const DEFAULT_MONTHS_BACK = 3;

/**
 * Dashboard de una sucursal (RF-031 a RF-034). `branchId` es obligatorio para
 * el backend (cada indicador reporta una sucursal a la vez, BR-039): para
 * `OPERATOR` queda fijo en la suya; `MANAGER`/`ADMIN` eligen cualquiera —
 * "dashboard completo", mismo criterio ya aprobado para el reporte de
 * cumplimiento logístico. La comparativa entre sucursales (RF-035) es una
 * pantalla aparte (`/dashboard/comparativa`), no un panel más aquí: no
 * acepta sucursal y su alcance es distinto (todas a la vez).
 */
export function DashboardPage() {
  const { user } = useAuth();
  useTransferRealtime(user);
  const canPickAnyBranch = canDashboard.queryAnyBranch(user?.role);

  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const [branchId, setBranchId] = useState(user?.branchId ?? "");
  const [months, setMonths] = useState(DEFAULT_MONTHS_BACK);

  const branchName = branchesQuery.data?.content.find((b) => b.id === branchId)?.name;

  return (
    <section>
      <div className="page__header">
        <h1>Dashboard</h1>
        {canDashboard.compareBranches(user?.role) ? <Link to="/dashboard/comparativa">Comparar sucursales</Link> : null}
      </div>

      <form className="filters" onSubmit={(event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="dashboard-branch">Sucursal</label>
          {canPickAnyBranch ? (
            <select id="dashboard-branch" value={branchId} onChange={(event) => setBranchId(event.target.value)}>
              <option value="">Selecciona…</option>
              {(branchesQuery.data?.content ?? []).map((branch) => (
                <option key={branch.id} value={branch.id}>
                  {branch.name}
                </option>
              ))}
            </select>
          ) : (
            <p id="dashboard-branch">{branchName ?? "Tu sucursal"}</p>
          )}
        </div>
        <div className="field">
          <label htmlFor="dashboard-months">Meses anteriores a comparar</label>
          <input
            id="dashboard-months"
            type="number"
            min={0}
            max={24}
            value={months}
            onChange={(event) => setMonths(Math.max(0, Math.min(24, Number(event.target.value) || 0)))}
          />
        </div>
      </form>

      {branchId === "" ? (
        <p className="state__hint">Selecciona una sucursal para ver su dashboard.</p>
      ) : (
        <>
          <SalesTrendPanel branchId={branchId} months={months} />
          <InventoryDemandPanel branchId={branchId} months={months} />
          <ActiveTransfersPanel branchId={branchId} />
          <ReplenishmentPanel branchId={branchId} />
        </>
      )}
    </section>
  );
}
