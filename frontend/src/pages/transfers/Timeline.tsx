import type { Transfer } from "../../types/api";

/**
 * Historial de la transferencia. No existe un endpoint de historial aparte:
 * `TransferResponse` ya trae cada hito en su propia columna (`requestedAt`,
 * `approvedAt`, `dispatchedAt`, `receivedAt` + responsables) porque el modelo
 * aprobado no contempla una tabla de historial separada (ver `docs/STATUS.md`,
 * fase de transferencias del backend). Esta vista solo ordena esas columnas
 * como una línea de tiempo; no inventa un estado que el backend no exponga.
 */
export function Timeline({ transfer }: { transfer: Transfer }) {
  const steps: Array<{ label: string; at: string | null; detail?: string }> = [
    { label: "Solicitada", at: transfer.requestedAt, detail: `Usuario #${transfer.requestedByUserId}` },
  ];

  if (transfer.status === "REJECTED") {
    steps.push({ label: "Rechazada", at: transfer.approvedAt });
  } else {
    steps.push({
      label: "Aprobada",
      at: transfer.approvedAt,
      detail: transfer.approvedByUserId ? `Usuario #${transfer.approvedByUserId}` : undefined,
    });
    steps.push({
      label: "Despachada",
      at: transfer.dispatchedAt,
      detail: [transfer.carrierName, transfer.estimatedArrivalDate ? `llegada estimada ${transfer.estimatedArrivalDate}` : null]
        .filter(Boolean)
        .join(" · ") || undefined,
    });
    steps.push({ label: "Recibida", at: transfer.receivedAt });
  }

  return (
    <ol className="timeline">
      {steps.map((step) => (
        <li key={step.label} className={step.at ? "timeline__step timeline__step--done" : "timeline__step"}>
          <span className="timeline__label">{step.label}</span>
          <span className="timeline__at">{step.at ? new Date(step.at).toLocaleString() : "Pendiente"}</span>
          {step.detail ? <span className="state__hint">{step.detail}</span> : null}
        </li>
      ))}
    </ol>
  );
}
