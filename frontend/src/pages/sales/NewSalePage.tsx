import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { listBranches } from "../../api/endpoints/branches";
import { listInventory } from "../../api/endpoints/inventory";
import { listProducts, listProductUnits } from "../../api/endpoints/products";
import { createSale } from "../../api/endpoints/sales";
import { queryKeys, queryPrefixes } from "../../api/queryClient";
import { useAuth } from "../../auth/useAuth";
import { FormErrorMessage } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { Modal } from "../../components/ui/Modal";
import { useIdempotencyKey } from "../../hooks/useIdempotencyKey";
import { formatCurrency } from "../../lib/currency";
import type { CreateSaleItemRequest } from "../../types/api";
import { productLabel } from "../products/useCatalog";
import { SaleLineRow, emptySaleLine, previewLineTotal, resolveEffectiveUnitPrice } from "./SaleLineRow";
import type { SaleLineDraft } from "./SaleLineRow";
import { useApplicablePriceLists, usePrices } from "./usePriceLists";

/**
 * Registro de venta (flujo A). El precio nunca se teclea: se toma de la
 * lista de precios seleccionada (`GET /price-lists/{id}/prices`), y el
 * descuento es el único valor de negocio que introduce el usuario —dentro
 * del rango que ya valida el backend (BR-019). El backend vuelve a resolver
 * el precio y a validar disponibilidad al confirmar; esto solo previsualiza.
 */
export function NewSalePage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isAdmin = user?.role === "ADMIN";
  const idempotency = useIdempotencyKey();

  const [branchId, setBranchId] = useState(isAdmin ? "" : user?.branchId ?? "");
  const [priceListId, setPriceListId] = useState("");
  const [lines, setLines] = useState<SaleLineDraft[]>([emptySaleLine()]);
  const [localErrors, setLocalErrors] = useState<{ branchId?: string; priceListId?: string; lines: Record<number, Record<string, string>> }>({ lines: {} });
  const [confirming, setConfirming] = useState(false);

  const branchesQuery = useQuery({ queryKey: queryKeys.branches({ active: true }), queryFn: () => listBranches({ active: true }) });
  const productsQuery = useQuery({
    queryKey: queryKeys.products({ active: true, page: 0, size: 200 }),
    queryFn: () => listProducts({ active: true, page: 0, size: 200 }),
  });
  const products = productsQuery.data?.content ?? [];
  const productsById = new Map(products.map((product) => [product.id, product]));

  const priceListsQuery = useApplicablePriceLists(branchId);
  const pricesQuery = usePrices(priceListId);

  // El precio de lista está fijado en la unidad base del producto: si una
  // línea usa una unidad alternativa, el precio previsualizado debe escalarse
  // por el mismo factor de conversión que ya aplica el backend al confirmar
  // (BR-019) — se resuelven las unidades de los productos presentes en el
  // formulario, mismo patrón de `useQueries` que `MovementsPage`.
  const lineProductIds = Array.from(new Set(lines.map((line) => line.productId).filter((id) => id !== "")));
  const productUnitQueries = useQueries({
    queries: lineProductIds.map((id) => ({
      queryKey: queryKeys.productUnits(id),
      queryFn: () => listProductUnits(id),
      staleTime: 60_000,
    })),
  });
  const productUnitsByProductId = new Map(
    lineProductIds.map((id, index) => [id, productUnitQueries[index]?.data ?? []]),
  );

  function effectiveUnitPriceFor(line: SaleLineDraft): number | undefined {
    return resolveEffectiveUnitPrice(
      pricesQuery.byProductId.get(line.productId),
      productUnitsByProductId.get(line.productId) ?? [],
      line.unitOfMeasureId,
    );
  }

  // Stock de la sucursal elegida, para mostrarlo junto a cada línea antes de
  // confirmar — el backend vuelve a validar disponibilidad al confirmar
  // (BR-022); esto es solo una previsualización, igual que el precio.
  const inventoryQuery = useQuery({
    queryKey: queryKeys.inventory({ branchId, size: 200 }),
    queryFn: () => listInventory({ branchId, size: 200 }),
    enabled: branchId !== "",
  });
  const stockByProductId = new Map((inventoryQuery.data?.content ?? []).map((row) => [row.productId, row.quantityOnHand]));

  useEffect(() => {
    setPriceListId(priceListsQuery.defaultId);
    // Cambiar de sucursal invalida la lista de precios elegida: se
    // re-selecciona con el mismo criterio por defecto.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [branchId, priceListsQuery.defaultId]);

  const mutation = useMutation({
    mutationFn: () => {
      const items: CreateSaleItemRequest[] = lines.map((line) => ({
        productId: Number(line.productId),
        unitOfMeasureId: line.unitOfMeasureId ? Number(line.unitOfMeasureId) : null,
        quantity: Number(line.quantity),
        discountPercentage: line.discountPercentage ? Number(line.discountPercentage) : null,
      }));
      return createSale({ branchId: Number(branchId), priceListId: Number(priceListId), items }, idempotency.key);
    },
    onSuccess: (sale) => {
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.sales });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventory });
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventoryMovements });
      idempotency.renew();
      navigate(`/ventas/${sale.id}`);
    },
    onError: () => {
      // Un rechazo por stock o precio puede deberse a datos que ya cambiaron
      // (otra venta, un ajuste): se refresca el inventario en vez de dejar
      // que la pantalla siga mostrando una foto vieja.
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.inventory });
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };

  function updateLine(index: number, patch: Partial<SaleLineDraft>) {
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...patch } : line)));
  }

  function removeLine(index: number) {
    setLines((current) => current.filter((_, i) => i !== index));
  }

  function validate(): boolean {
    const errors: typeof localErrors = { lines: {} };
    if (!branchId) errors.branchId = "Selecciona una sucursal.";
    if (!priceListId) errors.priceListId = "No hay una lista de precios activa para esta sucursal.";

    const seenProducts = new Set<string>();
    lines.forEach((line, index) => {
      const lineErrors: Record<string, string> = {};
      if (!line.productId) lineErrors.productId = "Selecciona un producto.";
      else if (seenProducts.has(line.productId)) lineErrors.productId = "Este producto ya está en otra línea.";
      else seenProducts.add(line.productId);
      if (!line.quantity.trim() || !(Number(line.quantity) > 0)) lineErrors.quantity = "Cantidad mayor que cero.";
      // El precio nunca se teclea (viene de la lista seleccionada): si no hay
      // uno vigente para este producto, se bloquea aquí con un mensaje
      // accionable en vez de dejar que la venta falle recién al confirmar.
      if (line.productId && priceListId && pricesQuery.byProductId.get(line.productId) === undefined) {
        lineErrors.price = "Este producto no tiene un precio vigente en la lista seleccionada.";
      }
      if (Object.keys(lineErrors).length > 0) errors.lines[index] = lineErrors;
    });

    setLocalErrors(errors);
    return !errors.branchId && !errors.priceListId && Object.keys(errors.lines).length === 0;
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (validate()) setConfirming(true);
  }

  const previewTotal = lines.reduce((sum, line) => {
    const lineTotal = previewLineTotal(line.quantity, effectiveUnitPriceFor(line), line.discountPercentage);
    return sum + (lineTotal ?? 0);
  }, 0);

  return (
    <section>
      <h1>Nueva venta</h1>
      <form onSubmit={handleSubmit} noValidate>
        <div className="filters">
          <div className="field">
            <label htmlFor="sale-branch">Sucursal</label>
            {isAdmin ? (
              <select id="sale-branch" value={branchId} onChange={(event) => setBranchId(event.target.value)}>
                <option value="">Selecciona…</option>
                {(branchesQuery.data?.content ?? []).map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.name}
                  </option>
                ))}
              </select>
            ) : (
              <p id="sale-branch">{branchesQuery.data?.content.find((b) => b.id === branchId)?.name ?? "Tu sucursal"}</p>
            )}
            {localErrors.branchId ? <span role="alert" className="field__error">{localErrors.branchId}</span> : null}
          </div>

          <div className="field">
            <label htmlFor="sale-price-list">Lista de precios</label>
            <select id="sale-price-list" value={priceListId} onChange={(event) => setPriceListId(event.target.value)} disabled={branchId === ""}>
              <option value="">Selecciona…</option>
              {priceListsQuery.lists.map((list) => (
                <option key={list.id} value={list.id}>
                  {list.name} {list.branchId === null ? "(global)" : ""}
                </option>
              ))}
            </select>
            {localErrors.priceListId ? <span role="alert" className="field__error">{localErrors.priceListId}</span> : null}
          </div>
        </div>

        <h2>Productos</h2>
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                <th scope="col">Producto</th>
                <th scope="col">Stock en sucursal</th>
                <th scope="col">Unidad</th>
                <th scope="col">Cantidad</th>
                <th scope="col">Precio</th>
                <th scope="col">Descuento %</th>
                <th scope="col">Total línea (estimado)</th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              {lines.map((line, index) => (
                <SaleLineRow
                  key={index}
                  line={line}
                  index={index}
                  products={products}
                  unitPrice={effectiveUnitPriceFor(line)}
                  stockOnHand={stockByProductId.get(line.productId)}
                  onChange={updateLine}
                  onRemove={removeLine}
                  canRemove={lines.length > 1}
                  errors={localErrors.lines[index] ?? {}}
                />
              ))}
            </tbody>
          </table>
        </div>
        <button type="button" onClick={() => setLines((current) => [...current, emptySaleLine()])}>
          Agregar producto
        </button>

        <p className="sale-total">
          Total estimado: <strong>{formatCurrency(previewTotal)}</strong>
          <span className="state__hint"> — el total definitivo lo calcula el servidor al confirmar.</span>
        </p>

        <FormErrorMessage error={serverErrors.general ? mutation.error : null} />

        <div className="modal__actions">
          <button type="submit">Revisar venta</button>
        </div>
      </form>

      {confirming ? (
        <Modal
          title="Confirmar venta"
          onClose={() => {
            mutation.reset();
            setConfirming(false);
          }}
        >
          <ul>
            {lines.map((line, index) => (
              <li key={index}>
                {productLabel(productsById.get(line.productId), line.productId)} — {line.quantity}
                {line.discountPercentage ? ` (descuento ${line.discountPercentage}%)` : ""}
              </li>
            ))}
          </ul>
          <p>
            Total estimado: <strong>{formatCurrency(previewTotal)}</strong>
          </p>
          <FormErrorMessage error={mutation.error} />
          <div className="modal__actions">
            <button
              type="button"
              onClick={() => {
                mutation.reset();
                setConfirming(false);
              }}
              disabled={mutation.isPending}
            >
              Volver
            </button>
            <button type="button" onClick={() => mutation.mutate()} disabled={mutation.isPending}>
              {mutation.isPending ? "Confirmando…" : "Confirmar venta"}
            </button>
          </div>
        </Modal>
      ) : null}
    </section>
  );
}
