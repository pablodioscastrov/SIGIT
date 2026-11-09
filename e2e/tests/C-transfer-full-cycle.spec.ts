import { expect, test } from "@playwright/test";
import {
  RUN_ID,
  USERS,
  createBranch,
  createProduct,
  createUser,
  findBranchByCode,
  getAnyUnitOfMeasure,
  login,
  productLabel,
  seedStock,
} from "./support/api";
import { loginAs, logout } from "./support/ui";

/**
 * Flujo mínimo C (docs/TEST_STRATEGY.md §5): solicitar transferencia →
 * despachar → recibir → verificar ambos inventarios. Cada paso lo ejecuta el
 * rol real que lo hace en producción (RF-022 a RF-025): ADMIN solicita y
 * recibe en la sucursal destino recién creada (sin usuario propio sembrado),
 * el Gerente de origen aprueba, el Operador de origen despacha.
 */
test.describe("Flujo C — transferencia completa mueve el stock de una sucursal a otra", () => {
  test("solicitud → aprobación → despacho → recepción completa deja el stock exacto en origen y destino", async ({ page, request }) => {
    const adminToken = await login(request, USERS.admin);
    const originBranch = await findBranchByCode(request, adminToken, "SUC-001");
    const unit = await getAnyUnitOfMeasure(request, adminToken);

    const destinationBranch = await createBranch(request, adminToken, {
      code: `E2E-C-${RUN_ID}`,
      name: `Sucursal E2E Destino C ${RUN_ID}`,
    });

    const sku = `E2E-C-${RUN_ID}`;
    const productName = `Producto Transferencia C ${RUN_ID}`;
    const product = await createProduct(request, adminToken, {
      sku,
      name: productName,
      baseUnitOfMeasureId: unit.id,
      minimumStock: 0,
      unitPrice: 12,
    });

    const initialStock = 50;
    const transferQuantity = 10;
    await seedStock(request, adminToken, { branchId: originBranch.id, productId: product.id, quantity: initialStock });

    const manager = await createUser(request, adminToken, {
      name: `Gerente E2E C ${RUN_ID}`,
      email: `gerente.e2e.c.${RUN_ID}@inventario.local`,
      role: "MANAGER",
      branchId: originBranch.id,
    });
    const operator = await createUser(request, adminToken, {
      name: `Operador E2E C ${RUN_ID}`,
      email: `operador.e2e.c.${RUN_ID}@inventario.local`,
      role: "OPERATOR",
      branchId: originBranch.id,
    });

    const label = productLabel(sku, productName);
    const labelRegex = new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));

    // C1 — solicitud, la origina un ADMIN (destino todavía sin usuarios propios).
    await loginAs(page, USERS.admin);
    await page.goto("/transferencias/nueva");
    await page.getByLabel("Sucursal destino (la que recibe)").selectOption(destinationBranch.id);
    await page.getByLabel("Sucursal origen (la que envía)").selectOption(originBranch.id);
    await page.getByLabel("Producto de la línea 1").selectOption(product.id);
    await page.getByLabel("Cantidad de la línea 1").fill(String(transferQuantity));
    await page.getByRole("button", { name: "Solicitar transferencia" }).click();
    await expect(page).toHaveURL(/\/transferencias\/\d+$/);
    const transferUrl = page.url();
    await logout(page);

    // C2 — aprobación, el Gerente de la sucursal origen (cantidad ya viene precargada con lo solicitado).
    await loginAs(page, manager);
    await page.goto(transferUrl);
    await page.getByRole("button", { name: "Aprobar" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Aprobar" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    await logout(page);

    // D — despacho, el Operador de la sucursal origen.
    await loginAs(page, operator);
    await page.goto(transferUrl);
    await page.getByRole("button", { name: "Despachar" }).click();
    await page.getByLabel("Transportista").fill("Transportes E2E");
    await page.getByLabel("Llegada estimada").fill("2026-09-15");
    await page.getByRole("dialog").getByRole("button", { name: "Continuar" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Confirmar despacho" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    await logout(page);

    // E — recepción completa, ADMIN en representación de la sucursal destino.
    await loginAs(page, USERS.admin);
    await page.goto(transferUrl);
    await page.getByRole("button", { name: "Recibir" }).click();
    await page.getByLabel(`Cantidad recibida de ${label}`).fill(String(transferQuantity));
    await page.getByRole("dialog").getByRole("button", { name: "Continuar" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Registrar recepción" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    await expect(page.getByText("Recibida completa")).toBeVisible();

    // Ambos inventarios: origen descontado, destino incrementado, exactamente lo transferido.
    await page.goto("/inventario");
    await page.getByLabel("Sucursal").selectOption(originBranch.id);
    await page.getByLabel("Buscar producto").fill(sku);
    await expect(page.getByRole("row", { name: labelRegex }).locator("td").nth(2)).toHaveText(String(initialStock - transferQuantity));

    await page.getByLabel("Sucursal").selectOption(destinationBranch.id);
    await expect(page.getByRole("row", { name: labelRegex }).locator("td").nth(2)).toHaveText(String(transferQuantity));
  });
});
