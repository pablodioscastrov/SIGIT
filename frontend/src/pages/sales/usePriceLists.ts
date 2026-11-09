import { useQuery } from "@tanstack/react-query";
import { listPriceLists, listPrices } from "../../api/endpoints/sales";
import { queryKeys } from "../../api/queryClient";
import type { PriceList } from "../../types/api";

/**
 * Listas de precios aplicables a una sucursal: las propias de esa sucursal
 * más las globales (`branchId === null`). Se preselecciona la de sucursal si
 * existe y, si no, la global — el mismo orden de prioridad que aplica el
 * backend cuando la venta no especifica `priceListId` (BR-030).
 *
 * <p>Esto no es una regla de negocio nueva en el cliente: la venta siempre
 * envía el `priceListId` que quedó seleccionado —nunca se omite para que el
 * backend "adivine"— así que lo que se previsualiza es exactamente lo que se
 * cobra. El cálculo solo decide qué opción aparece marcada por defecto en un
 * selector explícito que el usuario puede cambiar.
 */
export function useApplicablePriceLists(branchId: string) {
  const query = useQuery({
    queryKey: queryKeys.priceLists({ active: true }),
    queryFn: () => listPriceLists({ active: true }),
    enabled: branchId !== "",
  });
  const all = query.data?.content ?? [];
  const applicable = all.filter((list) => list.branchId === branchId || list.branchId === null);
  const branchSpecific = applicable.find((list) => list.branchId === branchId);
  const global = applicable.find((list) => list.branchId === null);
  const defaultId = branchSpecific?.id ?? global?.id ?? "";
  return { ...query, lists: applicable as PriceList[], defaultId };
}

/** Precios vigentes de una lista, indexados por producto para previsualizar. */
export function usePrices(priceListId: string) {
  const query = useQuery({
    queryKey: queryKeys.prices(priceListId || "none"),
    queryFn: () => listPrices(priceListId),
    enabled: priceListId !== "",
  });
  const byProductId = new Map((query.data ?? []).map((price) => [price.productId, price.unitPrice]));
  return { ...query, byProductId };
}
