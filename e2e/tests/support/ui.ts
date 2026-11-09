import { Page, expect } from "@playwright/test";

export async function loginAs(page: Page, credentials: { email: string; password: string }) {
  await page.goto("/login");
  await page.getByLabel("Correo electrónico").fill(credentials.email);
  await page.getByLabel("Contraseña").fill(credentials.password);
  await page.getByRole("button", { name: "Entrar" }).click();
  // Sale del login hacia /dashboard tras una sesión válida.
  await expect(page.getByRole("navigation", { name: "Navegación principal" })).toBeVisible();
}

export async function logout(page: Page) {
  await page.getByRole("button", { name: "Cerrar sesión" }).click();
  await expect(page).toHaveURL(/\/login$/);
}
