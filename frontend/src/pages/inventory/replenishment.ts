import type { InventoryRow } from "../../types/api";

/**
 * Estado de reabastecimiento de una fila.
 *
 * <p>Es <b>presentación</b>, no una regla de negocio propia del cliente:
 * reproduce exactamente el mismo umbral que el backend ya expone en el filtro
 * `lowStock` (`quantityOnHand <= minimumStock`, ver `InventoryRepository.search`
 * y BR-010). Se calcula aquí solo porque `InventoryResponse` no trae una
 * bandera por fila; el filtrado real lo sigue haciendo el servidor.
 */
export function needsReplenishment(row: InventoryRow): boolean {
  return Number(row.quantityOnHand) <= Number(row.minimumStock);
}
