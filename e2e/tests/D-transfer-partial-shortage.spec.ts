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
 * Flujo mínimo D (docs/TEST_STRATEGY.md §5): recepción parcial → faltante
 * visible. Mismo ciclo que el flujo C hasta el despacho; la diferencia está
 * solo en la recepción, que registra menos de lo despachado (BR-007/RF-026).
 */
test.describe("Flujo D — una recepción parcial deja el faltante visible", () => {
  test("recibir menos de lo despachado muestra el faltante exacto y el estado con faltante", async ({ page, request }) => {
    const adminToken = await login(request, USERS.admin);
    const originBranch = await findBranchByCode(request, adminToken, "SUC-001");
    const unit = await getAnyUnitOfMeasure(request, adminToken);

    const destinationBranch = await createBranch(request, adminToken, {
      code: `E2E-D-${RUN_ID}`,
      name: `Sucursal E2E Destino D ${RUN_ID}`,
    });

    const sku = `E2E-D-${RUN_ID}`;
    const productName = `Producto Transferencia D ${RUN_ID}`;
    const product = await createProduct(request, adminToken, {
      sku,
      name: productName,
      baseUnitOfMeasureId: unit.id,
      minimumStock: 0,
      unitPrice: 12,
    });

    const initialStock = 50;
    const dispatchedQuantity = 10;
    const receivedQuantity = 7;
    const expectedShortage = dispatchedQuantity - receivedQuantity;
    await seedStock(request, adminToken, { branchId: originBranch.id, productId: product.id, quantity: initialStock });

    const manager = await createUser(request, adminToken, {
      name: `Gerente E2E D ${RUN_ID}`,
      email: `gerente.e2e.d.${RUN_ID}@inventario.local`,
      role: "MANAGER",
      branchId: originBranch.id,
    });
    const operator = await createUser(request, adminToken, {
      name: `Operador E2E D ${RUN_ID}`,
      email: `operador.e2e.d.${RUN_ID}@inventario.local`,
      role: "OPERATOR",
      branchId: originBranch.id,
    });

    const label = productLabel(sku, productName);

    await loginAs(page, USERS.admin);
    await page.goto("/transferencias/nueva");
    await page.getByLabel("Sucursal destino (la que recibe)").selectOption(destinationBranch.id);
    await page.getByLabel("Sucursal origen (la que envía)").selectOption(originBranch.id);
    await page.getByLabel("Producto de la línea 1").selectOption(product.id);
    await page.getByLabel("Cantidad de la línea 1").fill(String(dispatchedQuantity));
    await page.getByRole("button", { name: "Solicitar transferencia" }).click();
    await expect(page).toHaveURL(/\/transferencias\/\d+$/);
    const transferUrl = page.url();
    await logout(page);

    await loginAs(page, manager);
    await page.goto(transferUrl);
    await page.getByRole("button", { name: "Aprobar" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Aprobar" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    await logout(page);

    await loginAs(page, operator);
    await page.goto(transferUrl);
    await page.getByRole("button", { name: "Despachar" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Continuar" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Confirmar despacho" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    await logout(page);

    // Recepción parcial: 7 de los 10 despachados — deja un faltante de 3 (BR-007).
    await loginAs(page, USERS.admin);
    await page.goto(transferUrl);
    await page.getByRole("button", { name: "Recibir" }).click();
    await page.getByLabel(`Cantidad recibida de ${label}`).fill(String(receivedQuantity));
    await page.getByRole("dialog").getByRole("button", { name: "Continuar" }).click();
    await expect(page.getByRole("dialog")).toContainText("dejará faltante");
    await page.getByRole("dialog").getByRole("button", { name: "Registrar recepción" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();

    await expect(page.getByText("Recibida con faltante")).toBeVisible();
    const labelRegex = new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    // Columnas de la tabla de líneas: Producto, Solicitado, Aprobado, Despachado, Recibido, Faltante, Tratamiento, (acciones).
    const lineRow = page.getByRole("row", { name: labelRegex });
    await expect(lineRow.locator("td").nth(5)).toHaveText(String(expectedShortage));
    await expect(lineRow).toContainText("Sin tratar");
    await expect(page.getByText(/tiene 1 línea\(s\) con faltante sin tratar/)).toBeVisible();
  });
});
