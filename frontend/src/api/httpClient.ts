import { env } from "../config/env";
import { ApiError } from "./ApiError";
import { getStoredToken } from "../auth/session";
import type { ApiErrorBody } from "../types/api";

/**
 * Cliente HTTP centralizado: único lugar donde se arma una petición a la API.
 *
 * <p>Responsabilidades: resolver la URL base desde configuración (nunca
 * hardcodeada), adjuntar el token, normalizar toda falla a {@link ApiError} y
 * avisar cuando la sesión deja de ser válida.
 *
 * <p>Usa `fetch` nativo en vez de una librería HTTP: lo que necesitamos —JSON,
 * encabezados, cancelación— está cubierto por la plataforma, y así el manejo
 * de errores queda explícito en una función legible en lugar de repartido en
 * la configuración de interceptores de un tercero. Esto permitió además
 * eliminar `axios` de las dependencias.
 */

type UnauthorizedHandler = () => void;

let onUnauthorized: UnauthorizedHandler = () => {};

/**
 * Registra qué hacer cuando el servidor responde 401. Lo llama el proveedor de
 * sesión al montarse; el cliente HTTP no importa el contexto de React para
 * evitar una dependencia circular entre capas.
 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  onUnauthorized = handler;
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  /** Se serializan omitiendo `undefined` y `null`, para no mandar `?x=undefined`. */
  query?: Record<string, string | number | boolean | undefined | null>;
  signal?: AbortSignal;
  /** Obligatorio en las operaciones de creación repetible (docs/API_DESIGN.md, sección 2). */
  idempotencyKey?: string;
}

function buildUrl(path: string, query?: RequestOptions["query"]): string {
  const url = new URL(`${env.apiBaseUrl}${path}`);
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== null) {
      url.searchParams.append(key, String(value));
    }
  }
  return url.toString();
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, query, signal, idempotencyKey } = options;

  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  const token = getStoredToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (idempotencyKey) {
    headers["Idempotency-Key"] = idempotencyKey;
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (cause) {
    // No hubo respuesta: sin red, servidor caído o petición cancelada.
    throw ApiError.network(cause);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  if (response.ok) {
    return (await response.json()) as T;
  }

  let apiError: ApiError;
  try {
    apiError = ApiError.fromBody(response.status, (await response.json()) as ApiErrorBody);
  } catch {
    // El cuerpo no siguió el sobre uniforme (p. ej. un 502 en HTML de un proxy).
    apiError = ApiError.malformed(response.status);
  }

  if (apiError.isUnauthorized) {
    // El token expiró o dejó de ser válido: la sesión local ya no sirve.
    // Un 403 NO entra aquí — ahí la sesión es válida, solo faltan permisos.
    onUnauthorized();
  }

  throw apiError;
}

export interface DownloadResult {
  blob: Blob;
  filename: string;
}

/**
 * Descarga binaria (los reportes exportables, BR-056: siempre `.xlsx`, nunca
 * JSON). No reutiliza {@link apiRequest} porque una respuesta exitosa no es
 * un cuerpo JSON sino un `Blob`, y el nombre de archivo viaja en
 * `Content-Disposition`, no en el cuerpo — el resto (adjuntar token,
 * normalizar el error a {@link ApiError}, avisar en un 401) es igual.
 */
export async function apiDownload(path: string, options: { query?: RequestOptions["query"] } = {}): Promise<DownloadResult> {
  const headers: Record<string, string> = {};
  const token = getStoredToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, options.query), { method: "GET", headers });
  } catch (cause) {
    throw ApiError.network(cause);
  }

  if (!response.ok) {
    let apiError: ApiError;
    try {
      apiError = ApiError.fromBody(response.status, (await response.json()) as ApiErrorBody);
    } catch {
      apiError = ApiError.malformed(response.status);
    }
    if (apiError.isUnauthorized) {
      onUnauthorized();
    }
    throw apiError;
  }

  const blob = await response.blob();
  const filename = filenameFrom(response.headers.get("Content-Disposition"));
  return { blob, filename };
}

/**
 * El backend manda dos formas de `filename` en `Content-Disposition`: la
 * simple (`filename="=?UTF-8?Q?...?="`, RFC 2047 — para clientes viejos que
 * no entienden la extendida) y la extendida (`filename*=UTF-8''...`, RFC
 * 5987 — UTF-8 real, solo percent-encoded). Hay que preferir siempre la
 * extendida: la simple aquí NO es el nombre de archivo en texto plano, es la
 * forma *encoded-word* de RFC 2047, y tomarla tal cual dejaría el archivo
 * descargado con un nombre ilegible como `=?UTF-8?Q?movimientos...?=`.
 */
function filenameFrom(disposition: string | null): string {
  if (!disposition) return "reporte.xlsx";
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (extended) return decodeURIComponent(extended[1].trim());
  const simple = /filename="?([^";]+)"?/i.exec(disposition);
  return simple ? simple[1] : "reporte.xlsx";
}
