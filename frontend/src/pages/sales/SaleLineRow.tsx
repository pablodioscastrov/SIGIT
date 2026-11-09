import { useQuery } from "@tanstack/react-query";
import { listProductUnits } from "../../api/endpoints/products";
import { queryKeys } from "../../api/queryClient";
import { formatCurrency } from "../../lib/currency";
import type { Product, ProductUnit } from "../../types/api";

export interface SaleLineDraft {
  productId: string;
  unitOfMeasureId: string;
  quantity: string;
  discountPercentage: string;
}

export function emptySaleLine(): SaleLineDraft {
  return { productId: "", unitOfMeasureId: "", quantity: "", discountPercentage: "" };
}

/** Precio unitario efectivo para la unidad elegida en la línea: el precio de
 * lista está fijado en la unidad base del producto (RF-011); si la línea usa
 * una unidad alternativa, se escala por el mismo factor de conversión que el
 * backend aplica al confirmar la venta (BR-019) — la misma idea que ya usa el
 * historial de movimientos (`MovementsPage.baseUnitEquivalent`) para mostrar
 * el equivalente en unidad base, aplicada aquí al precio en vez de a la
 * cantidad. */
export function resolveEffectiveUnitPrice(
  basePrice: number | undefined,
  units: ProductUnit[],
  unitOfMeasureId: string,
): number | undefined {
  if (basePrice === undefined || !unitOfMeasureId) return basePrice;
  const unit = units.find((candidate) => candidate.unitOfMeasureId === unitOfMeasureId);
  if (!unit || unit.baseUnit) return basePrice;
  return Number((basePrice * unit.conversionFactorToBase).toFixed(4));
}

/** Total de línea **previsualizado en el cliente**: aritmética simple para
 * mostrar un estimado antes de confirmar. La cifra que cuenta es siempre la
 * que el backend calcula y devuelve al confirmar (BigDecimal, HALF_UP) — esto
 * nunca se usa para decidir ni para persistir nada. */
export function previewLineTotal(quantity: string, unitPrice: number | undefined, discountPercentage: string): number | null {
  const qty = Number(quantity);
  if (unitPrice === undefined || !quantity.trim() || Number.isNaN(qty)) return null;
  const discount = discountPercentage.trim() ? Number(discountPercentage) : 0;
  const subtotal = qty * unitPrice;
  return subtotal - (subtotal * discount) / 100;
}

export function SaleLineRow({
  line,
  index,
  products,
  unitPrice,
  stockOnHand,
  onChange,
  onRemove,
  canRemove,
  errors,
}: {
  line: SaleLineDraft;
  index: number;
  products: Product[];
  /** Precio unitario ya ajustado a la unidad elegida en esta línea (ver {@link resolveEffectiveUnitPrice}). */
  unitPrice: number | undefined;
  /** Stock del producto en la sucursal elegida — solo previsualización, el backend vuelve a validar disponibilidad al confirmar. */
  stockOnHand: number | undefined;
  onChange: (index: number, patch: Partial<SaleLineDraft>) => void;
  onRemove: (index: number) => void;
  canRemove: boolean;
  errors: Record<string, string>;
}) {
  const unitsQuery = useQuery({
    queryKey: queryKeys.productUnits(line.productId || "none"),
    queryFn: () => listProductUnits(line.productId),
    enabled: line.productId !== "",
  });

  const preview = previewLineTotal(line.quantity, unitPrice, line.discountPercentage);

  return (
    <tr>
      <td>
        <select
          aria-label={`Producto de la línea ${index + 1}`}
          value={line.productId}
          onChange={(event) => onChange(index, { productId: event.target.value, unitOfMeasureId: "" })}
        >
          <option value="">Selecciona…</option>
          {products.map((product) => (
            <option key={product.id} value={product.id}>
              {product.sku} — {product.name}
            </option>
          ))}
        </select>
        {errors.productId ? <span role="alert" className="field__error">{errors.productId}</span> : null}
      </td>
      <td>{line.productId ? (stockOnHand !== undefined ? stockOnHand : "Sin registrar") : "—"}</td>
      <td>
        <select
          aria-label={`Unidad de la línea ${index + 1}`}
          value={line.unitOfMeasureId}
          onChange={(event) => onChange(index, { unitOfMeasureId: event.target.value })}
          disabled={line.productId === ""}
        >
          <option value="">Unidad base</option>
          {(unitsQuery.data ?? []).map((unit) => (
            <option key={unit.unitOfMeasureId} value={unit.unitOfMeasureId}>
              {unit.unitCode}
            </option>
          ))}
        </select>
      </td>
      <td>
        <input
          aria-label={`Cantidad de la línea ${index + 1}`}
          inputMode="decimal"
          value={line.quantity}
          onChange={(event) => onChange(index, { quantity: event.target.value })}
        />
        {errors.quantity ? <span role="alert" className="field__error">{errors.quantity}</span> : null}
      </td>
      <td>
        {line.productId ? (unitPrice !== undefined ? formatCurrency(unitPrice) : "Sin precio vigente") : "—"}
        {errors.price ? <span role="alert" className="field__error">{errors.price}</span> : null}
      </td>
      <td>
        <input
          aria-label={`Descuento de la línea ${index + 1}`}
          inputMode="decimal"
          value={line.discountPercentage}
          onChange={(event) => onChange(index, { discountPercentage: event.target.value })}
        />
      </td>
      <td>{preview !== null ? formatCurrency(preview) : "—"}</td>
      <td>
        <button type="button" onClick={() => onRemove(index)} disabled={!canRemove}>
          Quitar
        </button>
      </td>
    </tr>
  );
}
