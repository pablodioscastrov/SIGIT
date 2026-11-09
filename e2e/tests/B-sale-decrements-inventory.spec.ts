import { expect, test } from "@playwright/test";
import { RUN_ID, USERS, createProduct, createUser, findBranchByCode, getAnyUnitOfMeasure, login, productLabel, seedStock } from "./support/api";
import { loginAs } from "./support/ui";

/**
 * Flujo mínimo B (docs/TEST_STRATEGY.md §5): login → venta → verificar
 * decremento y movimiento. El stock inicial se siembra por API (mismo
 * mecanismo que usaría un ADMIN real, `POST /inventory/adjustments`) para
 * que la prueba se concentre en el flujo de venta en sí, no en repetir el
 * flujo A.
 */
test.describe("Flujo B — una venta decrementa el inventario y deja su movimiento", () => {
  test("MANAGER vende parte del stock y ambos, inventario y movimiento, reflejan exactamente la cantidad vendida", async ({ page, request }) => {
    const adminToken = await login(request, USERS.admin);
    const branch = await findBranchByCode(request, adminToken, "SUC-001");
    const unit = await getAnyUnitOfMeasure(request, adminToken);

    const sku = `E2E-B-${RUN_ID}`;
    const productName = `Producto Venta E2E ${RUN_ID}`;
    const product = await createProduct(request, adminToken, {
      sku,
      name: productName,
      baseUnitOfMeasureId: unit.id,
      minimumStock: 0,
      unitPrice: 25,
    });

    const initialStock = 20;
    const soldQuantity = 6;
    const expectedRemaining = String(initialStock - soldQuantity);
    await seedStock(request, adminToken, { branchId: branch.id, productId: product.id, quantity: initialStock });

    const manager = await createUser(request, adminToken, {
      name: `Gerente E2E ${RUN_ID}`,
      email: `gerente.e2e.${RUN_ID}@inventario.local`,
      role: "MANAGER",
      branchId: branch.id,
    });

    await loginAs(page, manager);

    await page.goto("/ventas/nueva");
    await page.getByLabel("Producto de la línea 1").selectOption(product.id);
    await page.getByLabel("Cantidad de la línea 1").fill(String(soldQuantity));
    await page.getByRole("button", { name: "Revisar venta" }).click();

    const confirmModal = page.getByRole("dialog", { name: "Confirmar venta" });
    await expect(confirmModal).toBeVisible();
    await confirmModal.getByRole("button", { name: "Confirmar venta" }).click();
    await expect(page).toHaveURL(/\/ventas\/\d+$/);

    await page.goto("/inventario");
    await page.getByLabel("Sucursal").selectOption(branch.id);
    await page.getByLabel("Buscar producto").fill(sku);
    const row = page.getByRole("row", { name: new RegExp(productLabel(sku, productName).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")) });
    await expect(row.locator("td").nth(2)).toHaveText(expectedRemaining);

    await page.goto(`/inventario/movimientos?branchId=${branch.id}&productId=${product.id}&reason=VENTA`);
    // Columnas de MovementsPage: Fecha, Producto, Sucursal, Tipo, Motivo, Cantidad, Origen, Notas.
    const movementRow = page.getByRole("row", { name: /VENTA/ });
    await expect(movementRow).toBeVisible();
    await expect(movementRow.locator("td").nth(4)).toHaveText("VENTA");
    await expect(movementRow.locator("td").nth(5)).toContainText(String(soldQuantity));
  });
});
