import type { Role } from "../types/api";

/**
 * Qué le mostramos a cada rol.
 *
 * <p><b>Esto es experiencia de usuario, no seguridad.</b> Ocultar una opción
 * evita que alguien llegue a una pantalla donde solo recibiría errores; no
 * impide nada. La autorización real vive en el backend
 * (`@PreAuthorize` + validación por sucursal, docs/API_DESIGN.md sección 6):
 * si un usuario fuerza la URL o llama a la API directamente, el servidor
 * responde 403 y la interfaz lo muestra como tal. Por eso ningún permiso se
 * "calcula" aquí más allá de lo necesario para armar el menú.
 *
 * <p>Las reglas de negocio (qué stock alcanza, si una transición es válida)
 * no se replican en el cliente en absoluto.
 */

export interface NavItem {
  label: string;
  path: string;
  /** Sin roles = visible para cualquier usuario autenticado. */
  roles?: Role[];
}

export const NAV_ITEMS: NavItem[] = [
  { label: "Dashboard", path: "/dashboard" },
  { label: "Inventario", path: "/inventario" },
  { label: "Alertas", path: "/inventario/alertas" },
  { label: "Productos", path: "/productos" },
  { label: "Proveedores", path: "/proveedores" },
  { label: "Compras", path: "/compras" },
  { label: "Ventas", path: "/ventas" },
  { label: "Transferencias", path: "/transferencias" },
  { label: "Logística", path: "/logistica/cumplimiento" },
  { label: "Sucursales", path: "/sucursales" },
  { label: "Usuarios", path: "/usuarios", roles: ["ADMIN"] },
];

export function canSee(item: NavItem, role: Role | undefined): boolean {
  if (!item.roles) return true;
  return role !== undefined && item.roles.includes(role);
}

export function visibleNavItems(role: Role | undefined): NavItem[] {
  return NAV_ITEMS.filter((item) => canSee(item, role));
}

/**
 * Qué acciones ofrece la interfaz a cada rol. Es un espejo de la tabla de
 * autorización del backend (docs/API_DESIGN.md, sección 6) y sirve para no
 * ofrecer un botón que solo devolvería 403 — <b>no</b> para autorizar.
 * Si alguien llama a la API igualmente, el backend responde 403 y la pantalla
 * lo muestra tal cual.
 */
export const can = {
  /** POST/PATCH de productos y de sus unidades. */
  writeProducts: (role: Role | undefined): boolean => role === "OPERATOR" || role === "ADMIN",
  /** Alta en el catálogo global de unidades: más estricto que el resto del módulo. */
  createUnitOfMeasure: (role: Role | undefined): boolean => role === "ADMIN",
  /** Ajuste manual de inventario. */
  adjustInventory: (role: Role | undefined): boolean => role === "OPERATOR" || role === "ADMIN",
};

/**
 * Alcance por sucursal: `ADMIN` opera sobre cualquiera; el resto solo sobre la
 * suya (`AuthorizationService.requireBranchAccess`). La <i>lectura</i> de
 * inventario es abierta a cualquier sucursal (RF-003), así que esto aplica
 * únicamente a las escrituras.
 */
export function canWriteInBranch(user: { role: Role; branchId: string | null } | null, branchId: string | null): boolean {
  if (!user || !branchId) return false;
  if (user.role === "ADMIN") return true;
  return user.branchId === branchId;
}

/**
 * Todo el módulo de usuarios —lectura y escritura— es exclusivo de ADMIN
 * (docs/API_DESIGN.md, sección 7.2, UC-14). La ruta `/usuarios` ya está
 * protegida con `RequireRole`; esto solo hace explícito el mismo criterio
 * dentro del propio componente, igual que el resto de módulos.
 */
export const canUsers = {
  write: (role: Role | undefined): boolean => role === "ADMIN",
};

/** Lectura de sucursales abierta a cualquier rol (RF-003); alta exclusiva de ADMIN (UC-15). */
export const canBranches = {
  write: (role: Role | undefined): boolean => role === "ADMIN",
};

/**
 * Proveedores (BR-058, reduce el alcance de BR-049 por instrucción
 * explícita): `OPERATOR` pasa a solo lectura; crear/editar/activar/desactivar
 * es `MANAGER`+`ADMIN`; eliminar (real, no reversible) queda exclusivo de
 * `ADMIN` — ni siquiera `MANAGER`.
 */
export const canSuppliers = {
  write: (role: Role | undefined): boolean => role === "MANAGER" || role === "ADMIN",
  delete: (role: Role | undefined): boolean => role === "ADMIN",
};

export const canPurchases = {
  /** Crear orden, cancelarla y registrar recepción — MANAGER incluido, mismas capacidades que ADMIN. */
  write: (role: Role | undefined): boolean => role === "OPERATOR" || role === "MANAGER" || role === "ADMIN",
};

export const canSales = {
  /** Registrar y gestionar (incluida la devolución de) una venta — MANAGER incluido, BR-053. */
  write: (role: Role | undefined): boolean => role === "OPERATOR" || role === "MANAGER" || role === "ADMIN",
};

/**
 * Acciones de transferencias por estado (docs/API_DESIGN.md, sección 6). Cada
 * una exige además pertenecer a la sucursal correcta —origen o destino según
 * la acción— salvo `ADMIN`; eso lo resuelve `canWriteInBranch`, no esta tabla.
 */
export const canTransfers = {
  /** Solicitar: la origina la sucursal destino. */
  request: (role: Role | undefined): boolean => role === "OPERATOR" || role === "MANAGER" || role === "ADMIN",
  /** Aprobar/rechazar: Gerente de la sucursal origen. */
  approve: (role: Role | undefined): boolean => role === "MANAGER" || role === "ADMIN",
  /** Despachar: Operador o Gerente de la sucursal origen. */
  dispatch: (role: Role | undefined): boolean => role === "OPERATOR" || role === "MANAGER" || role === "ADMIN",
  /** Recibir: Operador o Gerente de la sucursal destino. */
  receive: (role: Role | undefined): boolean => role === "OPERATOR" || role === "MANAGER" || role === "ADMIN",
  /** Tratar un faltante: Gerente de origen o destino. */
  treatDiscrepancy: (role: Role | undefined): boolean => role === "MANAGER" || role === "ADMIN",
};

export const canLogistics = {
  /** Clasificar/reclasificar rutas. */
  writeRoutes: (role: Role | undefined): boolean => role === "MANAGER" || role === "ADMIN",
};

/**
 * Alcance de sucursal en el dashboard (BR-039 a BR-043): distinto de
 * `canWriteInBranch` porque aquí `MANAGER` **no** queda limitado a la suya —
 * mismo "dashboard completo" ya aprobado para el reporte de cumplimiento
 * logístico. Solo `OPERATOR` se restringe a su propia sucursal.
 */
export const canDashboard = {
  queryAnyBranch: (role: Role | undefined): boolean => role === "MANAGER" || role === "ADMIN",
  compareBranches: (role: Role | undefined): boolean => role === "MANAGER" || role === "ADMIN",
};
