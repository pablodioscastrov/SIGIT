import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import type { DownloadResult } from "../../api/httpClient";
import { FormErrorMessage } from "../form/Field";
import { Modal } from "./Modal";

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

/**
 * Diálogo genérico para los cuatro reportes exportables en Excel (BR-056):
 * movimientos, ventas, transferencias y cumplimiento logístico. El backend
 * exige `dateFrom`/`dateTo` explícitos para exportar —a diferencia de los
 * listados paginados de cada pantalla, que sí completan un rango amplio por
 * defecto—, así que este es el único lugar que pide esas dos fechas.
 *
 * <p>No hay pantalla de PDF: esta app solo genera Excel (.xlsx), decisión ya
 * tomada y documentada en BR-056 (tiempo disponible y licencias de las
 * librerías de PDF).
 */
export function ExportDialog({
  title,
  onClose,
  onExport,
  defaultDateFrom,
  defaultDateTo,
}: {
  title: string;
  onClose: () => void;
  onExport: (range: { dateFrom: string; dateTo: string }) => Promise<DownloadResult>;
  defaultDateFrom?: string;
  defaultDateTo?: string;
}) {
  const [dateFrom, setDateFrom] = useState(defaultDateFrom ?? "");
  const [dateTo, setDateTo] = useState(defaultDateTo ?? "");
  const [localError, setLocalError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: async () => {
      const result = await onExport({ dateFrom: `${dateFrom}T00:00:00Z`, dateTo: `${dateTo}T23:59:59Z` });
      saveBlob(result.blob, result.filename);
    },
    onSuccess: () => onClose(),
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setLocalError(null);
    if (!dateFrom || !dateTo) {
      setLocalError("Selecciona la fecha de inicio y de fin.");
      return;
    }
    if (dateFrom > dateTo) {
      setLocalError("La fecha de inicio no puede ser posterior a la fecha de fin.");
      return;
    }
    mutation.mutate();
  }

  return (
    <Modal title={title} onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <p className="state__hint">Genera un archivo Excel (.xlsx) con los datos que coincidan con el rango de fechas y los filtros ya aplicados en pantalla.</p>
        <div className="field">
          <label htmlFor="export-date-from">Desde</label>
          <input id="export-date-from" type="date" value={dateFrom} onChange={(event) => setDateFrom(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="export-date-to">Hasta</label>
          <input id="export-date-to" type="date" value={dateTo} onChange={(event) => setDateTo(event.target.value)} />
        </div>
        {localError ? (
          <span role="alert" className="field__error">
            {localError}
          </span>
        ) : null}
        <FormErrorMessage error={mutation.error} />
        <div className="modal__actions">
          <button type="button" onClick={onClose} disabled={mutation.isPending}>
            Cancelar
          </button>
          <button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Generando…" : "Exportar"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
