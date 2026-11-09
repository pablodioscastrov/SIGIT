import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    // La URL de la API también llega por configuración en las pruebas: nunca
    // se escribe un host en el código, ni siquiera en un test.
    env: { VITE_API_BASE_URL: "http://api.test/api/v1" },
  },
});
