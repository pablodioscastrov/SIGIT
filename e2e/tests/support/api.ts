import { APIRequestContext, expect } from "@playwright/test";

export const API_BASE_URL = process.env.E2E_API_BASE_URL ?? "http://localhost:8080/api/v1";

/**
 * Credenciales de siembra de V4__seed_initial_users.sql — solo ADMIN se usa
 * directamente: es la cuenta que arma el resto de las fixtures por API, así
 * que su contraseña es la única que esta suite necesita poder confiar de
 * antemano. gerente.centro/operador.centro son datos de siembra "SOLO para
 * desarrollo" (mismo comentario en la migración): en el `docker compose` de
 * este entorno ya se editaron a mano durante fases anteriores del proyecto
 * (docs/STATUS.md), así que su contraseña ya no es fiable — MANAGER/OPERATOR
 * de prueba se crean frescos por test vía {@link createUser}.
 */
export const USERS = {
  admin: { email: "admin@inventario.local", password: "ChangeMe123!" },
} as const;

export const E2E_PASSWORD = "E2ePassw0rd!1";

/**
 * Sufijo único por ejecución de la suite (no por aserción): las cantidades y
 * resultados que se verifican dentro de cada prueba son siempre valores fijos
 * y deterministas (10, 3, 7, ...); este sufijo solo evita colisiones de clave
 * de negocio (SKU/código) contra datos que dejaron ejecuciones anteriores en
 * el volumen persistente de Postgres de `docker compose`, igual que las
 * claves de idempotencia que la propia aplicación ya genera por intento.
 */
export const RUN_ID = Date.now().toString(36);

export async function login(request: APIRequestContext, credentials: { email: string; password: string }): Promise<string> {
  const response = await request.post(`${API_BASE_URL}/auth/login`, { data: credentials });
  expect(response.ok(), `login failed for ${credentials.email}: ${await response.text()}`).toBeTruthy();
  const body = await response.json();
  return body.accessToken as string;
}

function authHeaders(token: string) {
  return { Authorization: `Bearer ${token}` };
}

export async function findBranchByCode(request: APIRequestContext, token: string, code: string) {
  const response = await request.get(`${API_BASE_URL}/branches?size=200`, { headers: authHeaders(token) });
  expect(response.ok()).toBeTruthy();
  const body = await response.json();
  const branch = (body.content as Array<{ id: string; code: string }>).find((b) => b.code === code);
  expect(branch, `branch with code ${code} not found`).toBeTruthy();
  return branch!;
}

/**
 * Cualquier unidad de medida sirve como base de un producto nuevo de prueba
 * — no importa cuál. No se busca por código fijo ("UN"/"CJ"/...): el
 * catálogo de siembra (V6__create_unit_of_measure_table.sql) es editable por
 * ADMIN (BR-050) y en este entorno de `docker compose` ya se editó durante
 * desarrollo manual, así que un código concreto no es un identificador
 * estable a través del tiempo.
 */
export async function getAnyUnitOfMeasure(request: APIRequestContext, token: string) {
  const response = await request.get(`${API_BASE_URL}/units-of-measure`, { headers: authHeaders(token) });
  expect(response.ok()).toBeTruthy();
  const units = (await response.json()) as Array<{ id: string; code: string }>;
  expect(units.length, "no units of measure exist").toBeGreaterThan(0);
  return units[0];
}

export async function createBranch(request: APIRequestContext, token: string, body: { code: string; name: string }) {
  const response = await request.post(`${API_BASE_URL}/branches`, { headers: authHeaders(token), data: body });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()) as { id: string; code: string; name: string };
}

export async function createUser(
  request: APIRequestContext,
  token: string,
  body: { name: string; email: string; role: "MANAGER" | "OPERATOR" | "ADMIN"; branchId: number | string | null },
) {
  const response = await request.post(`${API_BASE_URL}/users`, {
    headers: authHeaders(token),
    data: { name: body.name, email: body.email, password: E2E_PASSWORD, role: body.role, branchId: body.branchId },
  });
  expect(response.status(), await response.text()).toBe(201);
  return { email: body.email, password: E2E_PASSWORD };
}

export async function createSupplier(request: APIRequestContext, token: string, body: { name: string; taxId: string }) {
  const response = await request.post(`${API_BASE_URL}/suppliers`, { headers: authHeaders(token), data: body });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()) as { id: string; name: string };
}

export async function createProduct(
  request: APIRequestContext,
  token: string,
  body: { sku: string; name: string; baseUnitOfMeasureId: number | string; minimumStock: number; unitPrice: number },
) {
  const response = await request.post(`${API_BASE_URL}/products`, { headers: authHeaders(token), data: body });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()) as { id: string; sku: string; name: string };
}

/** Siembra stock inicial sin pasar por la UI (misma llamada que usaría un ADMIN real) — POST /inventory/adjustments, sin Idempotency-Key. */
export async function seedStock(
  request: APIRequestContext,
  token: string,
  body: { branchId: number | string; productId: number | string; quantity: number },
) {
  const response = await request.post(`${API_BASE_URL}/inventory/adjustments`, {
    headers: authHeaders(token),
    data: {
      branchId: Number(body.branchId),
      productId: Number(body.productId),
      direction: "INGRESO",
      quantity: body.quantity,
      notes: "Siembra de stock para prueba E2E",
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  return await response.json();
}

/** Espeja `productLabel()` del frontend (`frontend/src/pages/products/useCatalog.ts`) para construir los mismos aria-label que renderiza la UI. */
export function productLabel(sku: string, name: string): string {
  return `${sku} — ${name}`;
}

export async function getInventory(request: APIRequestContext, token: string, branchId: string, productId: string) {
  const response = await request.get(`${API_BASE_URL}/inventory?branchId=${branchId}&productId=${productId}`, {
    headers: authHeaders(token),
  });
  expect(response.ok()).toBeTruthy();
  const body = await response.json();
  return body.content[0] as { quantityOnHand: string } | undefined;
}
