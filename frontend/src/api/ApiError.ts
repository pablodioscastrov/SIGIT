import type { ApiErrorBody } from "../types/api";

/**
 * Error normalizado de la aplicación. Toda falla —de negocio, de permisos o
 * de red— llega a la interfaz con esta forma, para que las pantallas no
 * tengan que distinguir entre "el servidor respondió 422" y "no hubo
 * respuesta".
 *
 * La UI decide qué mostrar por `code`, nunca parseando `message`
 * (docs/API_DESIGN.md, sección 3: el mensaje no es estable entre versiones).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;
  readonly details?: Array<{ field: string; issue: string }>;

  constructor(params: {
    status: number;
    code: string;
    message: string;
    requestId?: string;
    details?: Array<{ field: string; issue: string }>;
  }) {
    super(params.message);
    this.name = "ApiError";
    this.status = params.status;
    this.code = params.code;
    this.requestId = params.requestId;
    this.details = params.details;
  }

  /** `status 0` = la petición nunca llegó al servidor (sin red, DNS, CORS, servidor caído). */
  static network(cause?: unknown): ApiError {
    return new ApiError({
      status: 0,
      code: "ERROR_DE_RED",
      message:
        cause instanceof Error && cause.name === "AbortError"
          ? "La petición se canceló."
          : "No se pudo contactar al servidor. Revisa tu conexión e inténtalo de nuevo.",
    });
  }

  /** Respuesta de error que no respeta el sobre uniforme (proxy intermedio, 502, HTML de error). */
  static malformed(status: number): ApiError {
    return new ApiError({
      status,
      code: "RESPUESTA_NO_RECONOCIDA",
      message: `El servidor respondió con un error inesperado (HTTP ${status}).`,
    });
  }

  static fromBody(status: number, body: ApiErrorBody): ApiError {
    return new ApiError({
      status,
      code: body.error.code,
      message: body.error.message,
      requestId: body.error.requestId,
      details: body.error.details,
    });
  }

  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isNetworkFailure(): boolean {
    return this.status === 0;
  }
}
