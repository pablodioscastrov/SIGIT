import { defineConfig, devices } from "@playwright/test";

const FRONTEND_URL = process.env.E2E_FRONTEND_URL ?? "http://localhost:3000";

/**
 * No hay `webServer`: estos flujos verifican exactamente lo que RT-003 exige
 * ("todo el sistema se levanta con `docker compose up`, sin configuración
 * manual adicional") — Playwright asume que ese comando ya corrió y solo se
 * conecta a él, en vez de levantar su propio servidor de desarrollo.
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [["list"]],
  timeout: 45_000,
  use: {
    baseURL: FRONTEND_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
