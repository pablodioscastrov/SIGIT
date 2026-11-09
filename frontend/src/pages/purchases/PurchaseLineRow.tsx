import { useQuery } from "@tanstack/react-query";
import { listProductUnits } from "../../api/endpoints/products";
import { queryKeys } from "../../api/queryClient";
import type { Product } from "../../types/api";

export interface PurchaseLineDraft {
  productId: string;
  unitOfMeasureId: string;
  quantityOrdered: string;
  unitPrice: string;
  discountPercentage: string;
}

export function emptyPurchaseLine(): PurchaseLineDraft {
  return { productId: "", unitOfMeasureId: "", quantityOrdered: "", unitPrice: "", discountPercentage: "" };
}

/**
 * Una línea del formulario de orden de compra. Es su propio componente (no
 * un `map` inline) porque cada fila necesita consultar las unidades
 * alternativas del producto que ella misma tiene seleccionado — un `useQuery`
 * por fila exige que cada fila sea una instancia de componente propia.
 */
export function PurchaseLineRow({
  line,
  index,
  products,
  onChange,
  onRemove,
  canRemove,
  errors,
}: {
  line: PurchaseLineDraft;
  index: number;
  products: Product[];
  onChange: (index: number, patch: Partial<PurchaseLineDraft>) => void;
  onRemove: (index: number) => void;
  canRemove: boolean;
  errors: Record<string, string>;
}) {
  const unitsQuery = useQuery({
    queryKey: queryKeys.productUnits(line.productId || "none"),
    queryFn: () => listProductUnits(line.productId),
    enabled: line.productId !== "",
  });

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
          value={line.quantityOrdered}
          onChange={(event) => onChange(index, { quantityOrdered: event.target.value })}
        />
        {errors.quantityOrdered ? <span role="alert" className="field__error">{errors.quantityOrdered}</span> : null}
      </td>
      <td>
        <input
          aria-label={`Precio unitario de la línea ${index + 1}`}
          inputMode="decimal"
          value={line.unitPrice}
          onChange={(event) => onChange(index, { unitPrice: event.target.value })}
        />
        {errors.unitPrice ? <span role="alert" className="field__error">{errors.unitPrice}</span> : null}
      </td>
      <td>
        <input
          aria-label={`Descuento de la línea ${index + 1}`}
          inputMode="decimal"
          value={line.discountPercentage}
          onChange={(event) => onChange(index, { discountPercentage: event.target.value })}
        />
      </td>
      <td>
        <button type="button" onClick={() => onRemove(index)} disabled={!canRemove}>
          Quitar
        </button>
      </td>
    </tr>
  );
}
