import { ApiError } from "../../api/ApiError";

/**
 * Códigos 409 que significan "el estado ya cambió mientras mirabas esta
 * pantalla" — otra persona aprobó, despachó, recibió o trató un faltante
 * primero. Se distinguen del resto de errores porque la respuesta correcta
 * no es "corrige el formulario", sino "vuelve a mirar el recurso": los datos
 * que el usuario tenía en pantalla ya no describen la realidad.
 */
const CONFLICT_CODES = new Set(["TRANSICION_INVALIDA", "RECEPCION_YA_REGISTRADA", "FALTANTE_YA_TRATADO"]);

export function isStateConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && CONFLICT_CODES.has(error.code);
}
