import { ApiError } from "../../api/ApiError";

/**
 * Traduce el error del backend a errores por campo, usando `details[]` del
 * sobre uniforme (docs/API_DESIGN.md, sección 3).
 *
 * <p>El error <b>nunca se oculta</b>: lo que no corresponde a un campo
 * concreto se devuelve como mensaje general para mostrarse junto al
 * formulario, incluido su código y su `requestId`.
 */
export interface FormErrors {
  fields: Record<string, string>;
  general?: string;
}

/** Códigos de negocio que apuntan inequívocamente a un campo del formulario. */
const FIELD_BY_CODE: Record<string, string> = {
  SKU_YA_EXISTE: "sku",
  CODIGO_UNIDAD_YA_EXISTE: "code",
  UNIDAD_YA_ASOCIADA: "unitOfMeasureId",
  UNIDAD_DE_MEDIDA_NO_ENCONTRADA: "unitOfMeasureId",
  CANTIDAD_INVALIDA: "quantity",
  STOCK_INSUFICIENTE: "quantity",
  NOTES_REQUERIDO: "notes",
  MOTIVO_INCOMPATIBLE_CON_DIRECCION: "reason",
  CODIGO_YA_EXISTE: "code",
  EMAIL_YA_EXISTE: "email",
  ADMIN_SIN_SUCURSAL: "branchId",
  SUCURSAL_REQUERIDA: "branchId",
  SUCURSAL_INACTIVA: "branchId",
};

export function toFormErrors(error: unknown): FormErrors {
  if (!(error instanceof ApiError)) {
    return { fields: {}, general: "Ocurrió un error inesperado." };
  }
  const fields: Record<string, string> = {};
  for (const detail of error.details ?? []) {
    fields[detail.field] = detail.issue;
  }
  const mapped = FIELD_BY_CODE[error.code];
  if (mapped && !fields[mapped]) {
    fields[mapped] = error.message;
  }
  // Si el error ya quedó adosado a un campo no se repite arriba; si no, se
  // muestra completo para no perder información del servidor.
  const attached = mapped !== undefined || (error.details?.length ?? 0) > 0;
  return { fields, general: attached ? undefined : error.message };
}
