import { useQuery } from "@tanstack/react-query";
import { listProducts, listUnitsOfMeasure } from "../../api/endpoints/products";
import { queryKeys } from "../../api/queryClient";
import type { Product, UnitOfMeasure } from "../../types/api";

/**
 * Catálogo global de unidades de medida. Cambia muy rara vez y varias
 * pantallas lo necesitan para traducir un `unitOfMeasureId` a un código
 * legible, así que se consulta una vez y se comparte por la caché.
 */
export function useUnitsOfMeasure() {
  const query = useQuery({
    queryKey: queryKeys.unitsOfMeasure(),
    queryFn: listUnitsOfMeasure,
    staleTime: 5 * 60_000,
  });
  const byId = new Map((query.data ?? []).map((unit) => [unit.id, unit]));
  return { ...query, units: query.data ?? ([] as UnitOfMeasure[]), byId };
}

/**
 * Índice de productos por id, para mostrar SKU y nombre en pantallas que solo
 * reciben `productId` (inventario y movimientos).
 *
 * <p><b>Limitación conocida:</b> `InventoryResponse` e `InventoryMovementResponse`
 * no incluyen los datos del producto, así que hay que resolverlos en el
 * cliente. Se hace con una sola consulta paginada amplia en lugar de una
 * petición por fila (N+1). La solución de fondo —añadir `sku` y `name` a esos
 * DTOs— cambia el contrato REST y requiere aprobación previa; queda anotada en
 * docs/STATUS.md.
 */
export function useProductIndex() {
  const query = useQuery({
    queryKey: queryKeys.products({ index: true }),
    queryFn: () => listProducts({ page: 0, size: 200 }),
    staleTime: 60_000,
  });
  const byId = new Map((query.data?.content ?? []).map((product) => [product.id, product]));
  return { byId, products: query.data?.content ?? ([] as Product[]), isPending: query.isPending };
}

export function productLabel(product: Product | undefined, productId: string): string {
  return product ? `${product.sku} — ${product.name}` : `Producto ${productId}`;
}
