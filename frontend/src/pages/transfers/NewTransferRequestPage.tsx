import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { listProducts } from "../../api/endpoints/products";
import { requestTransfer } from "../../api/endpoints/transfers";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { useAuth } from "../../auth/useAuth";
import { FormErrorMessage } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { useIdempotencyKey } from "../../hooks/useIdempotencyKey";
import type { CreateTransferItemRequest } from "../../types/api";
import { TransferLineRow, emptyTransferLine } from "./TransferLineRow";
import type { TransferLineDraft } from "./TransferLineRow";

/**
 * Solicitud de transferencia (flujo C1). La origina la sucursal destino
 * (`AuthorizationService.requireBranchAccess(destinationBranchId)`), así que
 * para un rol no-`ADMIN` el destino queda fijo en su propia sucursal — igual
 * convención que en compras y ventas — y el origen es cualquier otra sucursal
 * activa, sin restricción de pertenencia: se le está pidiendo a otra sucursal
 * que envíe mercancía.
 */
export function NewTransferRequestPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isAdmin = user?.role === "ADMIN";
  const idempotency = useIdempotencyKey();

  const [destinationBranchId, setDestinationBranchId] = useState(isAdmin ? "" : user?.branchId ?? "");
  const [originBranchId, setOriginBranchId] = useState("");
  const [urgency, setUrgency] = useState(false);
  const [lines, setLines] = useState<TransferLineDraft[]>([emptyTransferLine()]);
  const [localErrors, setLocalErrors] = useState<{ originBranchId?: string; destinationBranchId?: string; lines: Record<number, Record<string, string>> }>({ lines: {} });

  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const productsQuery = useQuery({
    queryKey: queryKeys.products({ active: true, page: 0, size: 200 }),
    queryFn: () => listProducts({ active: true, page: 0, size: 200 }),
  });
  const products = productsQuery.data?.content ?? [];

  const mutation = useMutation({
    mutationFn: () => {
      const items: CreateTransferItemRequest[] = lines.map((line) => ({
        productId: Number(line.productId),
        quantityRequested: Number(line.quantityRequested),
      }));
      return requestTransfer(
        { originBranchId: Number(originBranchId), destinationBranchId: Number(destinationBranchId), urgency, items },
        idempotency.key,
      );
    },
    onSuccess: (transfer) => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.transfers });
      idempotency.renew();
      navigate(`/transferencias/${transfer.id}`);
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };

  function updateLine(index: number, patch: Partial<TransferLineDraft>) {
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...patch } : line)));
  }

  function removeLine(index: number) {
    setLines((current) => current.filter((_, i) => i !== index));
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const errors: typeof localErrors = { lines: {} };
    if (!originBranchId) errors.originBranchId = "Selecciona la sucursal de origen.";
    if (!destinationBranchId) errors.destinationBranchId = "Selecciona la sucursal de destino.";
    if (originBranchId && destinationBranchId && originBranchId === destinationBranchId) {
      errors.originBranchId = "El origen y el destino deben ser sucursales distintas.";
    }

    const seenProducts = new Set<string>();
    lines.forEach((line, index) => {
      const lineErrors: Record<string, string> = {};
      if (!line.productId) lineErrors.productId = "Selecciona un producto.";
      else if (seenProducts.has(line.productId)) lineErrors.productId = "Este producto ya está en otra línea.";
      else seenProducts.add(line.productId);
      if (!line.quantityRequested.trim() || !(Number(line.quantityRequested) > 0)) lineErrors.quantityRequested = "Cantidad mayor que cero.";
      if (Object.keys(lineErrors).length > 0) errors.lines[index] = lineErrors;
    });

    setLocalErrors(errors);
    if (errors.originBranchId || errors.destinationBranchId || Object.keys(errors.lines).length > 0) return;
    mutation.mutate();
  }

  return (
    <section>
      <h1>Solicitar transferencia</h1>
      <form onSubmit={handleSubmit} noValidate>
        <div className="filters">
          <div className="field">
            <label htmlFor="transfer-destination">Sucursal destino (la que recibe)</label>
            {isAdmin ? (
              <select id="transfer-destination" value={destinationBranchId} onChange={(event) => setDestinationBranchId(event.target.value)}>
                <option value="">Selecciona…</option>
                {(branchesQuery.data?.content ?? []).map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.name}
                  </option>
                ))}
              </select>
            ) : (
              <p id="transfer-destination">{branchesQuery.data?.content.find((b) => b.id === destinationBranchId)?.name ?? "Tu sucursal"}</p>
            )}
            {localErrors.destinationBranchId ? <span role="alert" className="field__error">{localErrors.destinationBranchId}</span> : null}
          </div>

          <div className="field">
            <label htmlFor="transfer-origin">Sucursal origen (la que envía)</label>
            <select id="transfer-origin" value={originBranchId} onChange={(event) => setOriginBranchId(event.target.value)}>
              <option value="">Selecciona…</option>
              {(branchesQuery.data?.content ?? [])
                .filter((branch) => branch.id !== destinationBranchId)
                .map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.name}
                  </option>
                ))}
            </select>
            {localErrors.originBranchId ? <span role="alert" className="field__error">{localErrors.originBranchId}</span> : null}
          </div>

          <div className="field field--check">
            <label htmlFor="transfer-urgency">
              <input id="transfer-urgency" type="checkbox" checked={urgency} onChange={(event) => setUrgency(event.target.checked)} />
              Urgente
            </label>
          </div>
        </div>

        <h2>Productos solicitados</h2>
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                <th scope="col">Producto</th>
                <th scope="col">Cantidad</th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              {lines.map((line, index) => (
                <TransferLineRow
                  key={index}
                  line={line}
                  index={index}
                  products={products}
                  onChange={updateLine}
                  onRemove={removeLine}
                  canRemove={lines.length > 1}
                  errors={localErrors.lines[index] ?? {}}
                />
              ))}
            </tbody>
          </table>
        </div>
        <button type="button" onClick={() => setLines((current) => [...current, emptyTransferLine()])}>
          Agregar línea
        </button>

        <FormErrorMessage error={serverErrors.general ? mutation.error : null} />

        <div className="modal__actions">
          <button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Solicitando…" : "Solicitar transferencia"}
          </button>
        </div>
      </form>
    </section>
  );
}
