import type { Product } from "../../types/api";

export interface TransferLineDraft {
  productId: string;
  quantityRequested: string;
}

export function emptyTransferLine(): TransferLineDraft {
  return { productId: "", quantityRequested: "" };
}

/**
 * Línea de solicitud de transferencia. A diferencia de compras/ventas no hay
 * selector de unidad: el contrato no admite unidad alternativa aquí — la
 * línea siempre se registra en la unidad base del producto (ver
 * `CreateTransferItemRequest` en el backend).
 */
export function TransferLineRow({
  line,
  index,
  products,
  onChange,
  onRemove,
  canRemove,
  errors,
}: {
  line: TransferLineDraft;
  index: number;
  products: Product[];
  onChange: (index: number, patch: Partial<TransferLineDraft>) => void;
  onRemove: (index: number) => void;
  canRemove: boolean;
  errors: Record<string, string>;
}) {
  return (
    <tr>
      <td>
        <select
          aria-label={`Producto de la línea ${index + 1}`}
          value={line.productId}
          onChange={(event) => onChange(index, { productId: event.target.value })}
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
        <input
          aria-label={`Cantidad de la línea ${index + 1}`}
          inputMode="decimal"
          value={line.quantityRequested}
          onChange={(event) => onChange(index, { quantityRequested: event.target.value })}
        />
        {errors.quantityRequested ? <span role="alert" className="field__error">{errors.quantityRequested}</span> : null}
      </td>
      <td>
        <button type="button" onClick={() => onRemove(index)} disabled={!canRemove}>
          Quitar
        </button>
      </td>
    </tr>
  );
}
