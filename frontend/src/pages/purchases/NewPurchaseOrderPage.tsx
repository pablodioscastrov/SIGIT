import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { listProducts } from "../../api/endpoints/products";
import { createPurchaseOrder } from "../../api/endpoints/purchases";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { useAuth } from "../../auth/useAuth";
import { Field, FormErrorMessage } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { useIdempotencyKey } from "../../hooks/useIdempotencyKey";
import type { CreatePurchaseOrderItemRequest } from "../../types/api";
import { PurchaseLineRow, emptyPurchaseLine } from "./PurchaseLineRow";
import type { PurchaseLineDraft } from "./PurchaseLineRow";
import { useActiveSuppliers } from "./useSuppliers";

/**
 * Alta de orden de compra (RF-012/RF-013). Las líneas son estado local plano
 * —sin `react-hook-form`— siguiendo el mismo criterio de ADR-010/ADR-011:
 * cada línea es un puñado de campos sin validación cruzada entre ellas, así
 * que un array en `useState` sigue siendo más simple que adoptar una
 * librería nueva. El criterio para reconsiderarlo sigue en pie si estas
 * pantallas ganan complejidad (p. ej. al llegar a transferencias).
 */
export function NewPurchaseOrderPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isAdmin = user?.role === "ADMIN";
  const idempotency = useIdempotencyKey();

  const [supplierId, setSupplierId] = useState("");
  const [branchId, setBranchId] = useState(isAdmin ? "" : user?.branchId ?? "");
  const [paymentTerm, setPaymentTerm] = useState("");
  const [lines, setLines] = useState<PurchaseLineDraft[]>([emptyPurchaseLine()]);
  const [localErrors, setLocalErrors] = useState<{ general?: string; supplierId?: string; branchId?: string; lines: Record<number, Record<string, string>> }>({ lines: {} });

  const { suppliers } = useActiveSuppliers();
  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const productsQuery = useQuery({
    queryKey: queryKeys.products({ active: true, page: 0, size: 200 }),
    queryFn: () => listProducts({ active: true, page: 0, size: 200 }),
  });
  const products = productsQuery.data?.content ?? [];

  const mutation = useMutation({
    mutationFn: () => {
      const items: CreatePurchaseOrderItemRequest[] = lines.map((line) => ({
        productId: Number(line.productId),
        unitOfMeasureId: line.unitOfMeasureId ? Number(line.unitOfMeasureId) : null,
        quantityOrdered: Number(line.quantityOrdered),
        unitPrice: Number(line.unitPrice),
        discountPercentage: line.discountPercentage ? Number(line.discountPercentage) : null,
      }));
      return createPurchaseOrder(
        { supplierId: Number(supplierId), branchId: Number(branchId), paymentTerm: paymentTerm.trim() || null, items },
        idempotency.key,
      );
    },
    onSuccess: (order) => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.purchaseOrders });
      idempotency.renew();
      navigate(`/compras/${order.id}`);
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };

  function updateLine(index: number, patch: Partial<PurchaseLineDraft>) {
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...patch } : line)));
  }

  function removeLine(index: number) {
    setLines((current) => current.filter((_, i) => i !== index));
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const errors: typeof localErrors = { lines: {} };
    if (!supplierId) errors.supplierId = "Selecciona un proveedor.";
    if (!branchId) errors.branchId = "Selecciona una sucursal.";

    const seenProducts = new Set<string>();
    lines.forEach((line, index) => {
      const lineErrors: Record<string, string> = {};
      if (!line.productId) lineErrors.productId = "Selecciona un producto.";
      else if (seenProducts.has(line.productId)) lineErrors.productId = "Este producto ya está en otra línea.";
      else seenProducts.add(line.productId);
      if (!line.quantityOrdered.trim() || !(Number(line.quantityOrdered) > 0)) lineErrors.quantityOrdered = "Cantidad mayor que cero.";
      if (!line.unitPrice.trim() || !(Number(line.unitPrice) > 0)) lineErrors.unitPrice = "Precio mayor que cero.";
      if (Object.keys(lineErrors).length > 0) errors.lines[index] = lineErrors;
    });

    setLocalErrors(errors);
    if (errors.supplierId || errors.branchId || Object.keys(errors.lines).length > 0) return;
    mutation.mutate();
  }

  return (
    <section>
      <h1>Nueva orden de compra</h1>
      <form onSubmit={handleSubmit} noValidate>
        <div className="filters">
          <div className="field">
            <label htmlFor="order-supplier">Proveedor</label>
            <select id="order-supplier" value={supplierId} onChange={(event) => setSupplierId(event.target.value)}>
              <option value="">Selecciona…</option>
              {suppliers.map((supplier) => (
                <option key={supplier.id} value={supplier.id}>
                  {supplier.name}
                </option>
              ))}
            </select>
            {localErrors.supplierId ? <span role="alert" className="field__error">{localErrors.supplierId}</span> : null}
            {serverErrors.fields.supplierId ? <span role="alert" className="field__error">{serverErrors.fields.supplierId}</span> : null}
          </div>

          <div className="field">
            <label htmlFor="order-branch">Sucursal</label>
            {isAdmin ? (
              <select id="order-branch" value={branchId} onChange={(event) => setBranchId(event.target.value)}>
                <option value="">Selecciona…</option>
                {(branchesQuery.data?.content ?? []).map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.name}
                  </option>
                ))}
              </select>
            ) : (
              <p id="order-branch">{branchesQuery.data?.content.find((b) => b.id === branchId)?.name ?? "Tu sucursal"}</p>
            )}
            {localErrors.branchId ? <span role="alert" className="field__error">{localErrors.branchId}</span> : null}
          </div>

          <Field id="order-payment-term" label="Condición de pago" value={paymentTerm} onChange={(event) => setPaymentTerm(event.target.value)} maxLength={100} />
        </div>

        <h2>Líneas</h2>
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                <th scope="col">Producto</th>
                <th scope="col">Unidad</th>
                <th scope="col">Cantidad</th>
                <th scope="col">Precio unitario</th>
                <th scope="col">Descuento %</th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              {lines.map((line, index) => (
                <PurchaseLineRow
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
        <button type="button" onClick={() => setLines((current) => [...current, emptyPurchaseLine()])}>
          Agregar línea
        </button>

        <FormErrorMessage error={serverErrors.general ? mutation.error : null} />

        <div className="modal__actions">
          <button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Creando…" : "Crear orden"}
          </button>
        </div>
      </form>
    </section>
  );
}
