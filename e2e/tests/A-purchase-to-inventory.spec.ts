import { expect, test } from "@playwright/test";
import {
  RUN_ID,
  USERS,
  createProduct,
  createSupplier,
  createUser,
  findBranchByCode,
  getAnyUnitOfMeasure,
  login,
  productLabel,
} from "./support/api";
import { loginAs } from "./support/ui";

/**
 * Flujo mínimo A (docs/TEST_STRATEGY.md §5): login → registrar compra →
 * verificar inventario. Cubre navegador real → API real → PostgreSQL real
 * bajo `docker compose up`, la parte que ningún nivel de prueba anterior
 * ejercita (RT-003).
 */
test.describe("Flujo A — compra recibida incrementa el inventario", () => {
  test("OPERATOR crea una orden, la recibe completa y ve el stock exacto en Inventario", async ({ page, request }) => {
    const adminToken = await login(request, USERS.admin);
    const branch = await findBranchByCode(request, adminToken, "SUC-001");
    const unit = await getAnyUnitOfMeasure(request, adminToken);

    const sku = `E2E-A-${RUN_ID}`;
    const productName = `Producto Compra E2E ${RUN_ID}`;
    const supplier = await createSupplier(request, adminToken, {
      name: `Proveedor E2E ${RUN_ID}`,
      taxId: `900${RUN_ID}1`,
    });
    const product = await createProduct(request, adminToken, {
      sku,
      name: productName,
      baseUnitOfMeasureId: unit.id,
      minimumStock: 0,
      unitPrice: 15,
    });

    const quantity = "8";
    const unitPrice = "15";

    const operator = await createUser(request, adminToken, {
      name: `Operador E2E A ${RUN_ID}`,
      email: `operador.e2e.a.${RUN_ID}@inventario.local`,
      role: "OPERATOR",
      branchId: branch.id,
    });

    await loginAs(page, operator);

    await page.goto("/compras/nueva");
    await page.getByLabel("Proveedor").selectOption(supplier.id);
    await page.getByLabel("Producto de la línea 1").selectOption(product.id);
    await page.getByLabel("Cantidad de la línea 1").fill(quantity);
    await page.getByLabel("Precio unitario de la línea 1").fill(unitPrice);
    await page.getByRole("button", { name: "Crear orden" }).click();

    await expect(page).toHaveURL(/\/compras\/\d+$/);

    const receiveQtyInput = page.getByLabel(`Cantidad a recibir de ${product.id}`);
    const receivePriceInput = page.getByLabel(`Precio de recepción de ${product.id}`);
    await expect(receiveQtyInput).toBeVisible();
    await receiveQtyInput.fill(quantity);
    await receivePriceInput.fill(unitPrice);
    await page.getByRole("button", { name: "Continuar" }).click();

    const confirmModal = page.getByRole("dialog", { name: "Confirmar recepción" });
    await expect(confirmModal).toBeVisible();
    await confirmModal.getByRole("button", { name: "Registrar recepción" }).click();
    await expect(confirmModal).toBeHidden();
    // Recepción total: el estado de la orden pasa a "Recibida" y, con nada
    // pendiente, la columna de recepción desaparece del todo (no solo la fila).
    await expect(page.getByText("Recibida", { exact: true })).toBeVisible();

    // InventoryPage guarda sus filtros en estado local, no en la URL (a
    // diferencia de MovementsPage) — hay que aplicarlos desde los controles.
    await page.goto("/inventario");
    await page.getByLabel("Sucursal").selectOption(branch.id);
    await page.getByLabel("Buscar producto").fill(sku);
    const row = page.getByRole("row", { name: new RegExp(productLabel(sku, productName).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")) });
    await expect(row).toBeVisible();
    await expect(row.locator("td").nth(2)).toHaveText(quantity);
  });
});
