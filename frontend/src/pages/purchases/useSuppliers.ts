import { useQuery } from "@tanstack/react-query";
import { listSuppliers } from "../../api/endpoints/suppliers";
import { queryKeys } from "../../api/queryClient";

/** Catálogo de proveedores activos, compartido por listado y alta de orden de compra. */
export function useActiveSuppliers() {
  const query = useQuery({
    queryKey: queryKeys.suppliers({ active: true, size: 200 }),
    queryFn: () => listSuppliers({ active: true, size: 200 }),
    staleTime: 60_000,
  });
  const suppliers = query.data?.content ?? [];
  const byId = new Map(suppliers.map((supplier) => [supplier.id, supplier]));
  return { ...query, suppliers, byId };
}
