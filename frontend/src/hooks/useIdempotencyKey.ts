import { useState } from "react";

/**
 * Clave de idempotencia para una operación crítica (BR-017): compra, recepción
 * y venta la exigen en el encabezado `Idempotency-Key`.
 *
 * <p>Se genera una vez por intento de envío y se conserva a través de
 * reintentos del mismo envío (un fallo de red, un doble clic) para que el
 * backend los reconozca como el mismo pedido y no duplique el efecto. Se
 * renueva solo cuando el usuario inicia una operación genuinamente nueva
 * (`renew`) — p. ej. al reabrir el formulario tras haber completado o
 * cancelado la anterior.
 */
export function useIdempotencyKey() {
  const [key, setKey] = useState(() => crypto.randomUUID());
  return { key, renew: () => setKey(crypto.randomUUID()) };
}
