import type { DiscrepancyTreatment, TransferStatus } from "../../types/api";

export const TRANSFER_STATUS_LABELS: Record<TransferStatus, string> = {
  REQUESTED: "Solicitada",
  APPROVED: "Aprobada",
  REJECTED: "Rechazada",
  IN_TRANSIT: "En tránsito",
  RECEIVED_COMPLETE: "Recibida completa",
  RECEIVED_PARTIAL: "Recibida con faltante",
  CLOSED: "Cerrada",
};

export const DISCREPANCY_TREATMENT_LABELS: Record<DiscrepancyTreatment, string> = {
  REENVIO: "Reenvío",
  AJUSTE: "Ajuste (pérdida, sin reposición)",
  RECLAMACION: "Reclamación",
};

export const DISCREPANCY_TREATMENT_HINTS: Record<DiscrepancyTreatment, string> = {
  REENVIO: "Crea una nueva transferencia por la cantidad faltante.",
  AJUSTE: "El faltante se asume como pérdida; no se genera reposición.",
  RECLAMACION: "Abre un reclamo al responsable del traslado, sin mover stock.",
};

/** Terminal: ninguna acción avanza el estado desde aquí. */
export function isTerminalStatus(status: TransferStatus): boolean {
  return status === "REJECTED" || status === "RECEIVED_COMPLETE" || status === "CLOSED";
}
