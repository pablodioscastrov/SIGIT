import type { ReactNode } from "react";
import { ApiError } from "../../api/ApiError";

/**
 * Los tres estados que toda vista con datos remotos necesita, en un solo
 * lugar para que se vean y se comporten igual en toda la aplicación.
 */

export function LoadingState({ label = "Cargando…" }: { label?: string }) {
  return (
    <p role="status" aria-live="polite" className="state state--loading">
      {label}
    </p>
  );
}

export function EmptyState({ title, hint }: { title: string; hint?: ReactNode }) {
  return (
    <div className="state state--empty">
      <p>{title}</p>
      {hint ? <p className="state__hint">{hint}</p> : null}
    </div>
  );
}

/**
 * Mensajes por `code`, nunca por el texto del servidor
 * (docs/API_DESIGN.md, sección 3: `message` no es estable entre versiones).
 * Lo que no esté mapeado cae al mensaje del backend, que ya viene redactado
 * para mostrarse.
 */
const MESSAGES_BY_CODE: Record<string, string> = {
  ERROR_DE_RED: "No se pudo contactar al servidor. Revisa tu conexión e inténtalo de nuevo.",
  ROL_NO_AUTORIZADO: "Tu rol no tiene permiso para realizar esta acción.",
  SUCURSAL_NO_AUTORIZADA: "No tienes acceso a la sucursal solicitada.",
  NO_AUTENTICADO: "Tu sesión expiró. Vuelve a iniciar sesión.",
  RECURSO_NO_ENCONTRADO: "No encontramos lo que buscabas.",
  ERROR_INTERNO: "Ocurrió un error inesperado. Inténtalo de nuevo en unos momentos.",
};

export function errorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    return MESSAGES_BY_CODE[error.code] ?? error.message;
  }
  return "Ocurrió un error inesperado.";
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const apiError = error instanceof ApiError ? error : null;
  return (
    <div role="alert" className="state state--error">
      <p>{errorMessageFor(error)}</p>
      {/* El requestId es la forma de correlacionar con los logs del backend
          (CorrelationIdFilter): mostrarlo ahorra depuración a ciegas. */}
      {apiError?.requestId ? <p className="state__hint">Referencia: {apiError.requestId}</p> : null}
      {onRetry ? (
        <button type="button" onClick={onRetry}>
          Reintentar
        </button>
      ) : null}
    </div>
  );
}

/**
 * Une los tres estados con el resultado de una consulta, para que una pantalla
 * no tenga que repetir el mismo `if (isLoading) … if (error) …`.
 */
export function AsyncBoundary<T>({
  isLoading,
  error,
  data,
  onRetry,
  isEmpty,
  emptyTitle = "No hay datos para mostrar.",
  children,
}: {
  isLoading: boolean;
  error: unknown;
  data: T | undefined;
  onRetry?: () => void;
  isEmpty?: (data: T) => boolean;
  emptyTitle?: string;
  children: (data: T) => ReactNode;
}) {
  if (isLoading) return <LoadingState />;
  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  if (data === undefined) return null;
  if (isEmpty?.(data)) return <EmptyState title={emptyTitle} />;
  return <>{children(data)}</>;
}
